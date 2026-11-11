package com.music.bitchord.playback

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.connect.Command
import com.music.bitchord.data.connect.CommandAction
import com.music.bitchord.data.connect.ConnectDevice
import com.music.bitchord.data.connect.ConnectLimits
import com.music.bitchord.data.connect.ConnectLocal
import com.music.bitchord.data.connect.ConnectManager
import com.music.bitchord.data.connect.ConnectRepeat
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.connect.PlaybackState
import com.music.bitchord.data.connect.QueueEdit
import com.music.bitchord.data.connect.RemoteSession
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.wear.WearBridge
import dev.auden.dj.DjSession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Binds the ExoPlayer to Auden Connect ([ConnectManager]), the way [PartySync] binds it to a party.
 *
 * It lives in [PlaybackService] for the same reason: a handed-over session has to keep playing with the
 * screen off. The two directions are kept apart as in PartySync:
 *
 *  - **Outbound** is what the player does. [onPlayerEvents] is the player's own event stream (the active
 *    device then tells the room), and [onUserIntent] is the one door that is the *user*: only a user action
 *    on this phone can make it the active device. A pause that a release imposed, or a player event that
 *    nothing here asked for, never claims, so a remote session stays put until the user presses play here.
 *  - **Inbound** is [ConnectLocal]: takeovers write straight to the ExoPlayer underneath the session's
 *    wrapper, so they are not mistaken for user intent (and are not published to a party). Remote commands
 *    go through the session player instead ([commandDoor]) so a skip or a seek gets the same crossfade
 *    handling as a button press on the phone.
 *
 * **Mirror mode** is the other half of the story: while another device is the active one, this phone's player
 * shows that session and is silent. [ConnectMirror.kt] explains the model; here [hydrator] keeps the idle
 * ExoPlayer's queue equal to the session's and [speaker] answers the session player's questions from it.
 *
 * Only YouTube songs travel: a local file, another source's track and the DJ's spoken clips are left out of
 * the shared queue (and a queue with nothing shareable in it is not claimed).
 */
class ConnectSync(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The raw ExoPlayer, read fresh every time: the service swaps players at a crossfade. */
    private val player: () -> Player?,
    /** The session's player, whose actions are the user's: used to carry out a remote's commands. */
    private val commandDoor: () -> Player?,
    private val manager: ConnectManager = ConnectManager.shared,
    /** What the session player reports and sends commands through while this phone mirrors. */
    val speaker: ConnectSpeaker = ConnectSpeaker(manager),
) : ConnectLocal {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var jobs = mutableListOf<Job>()
    private var joinedRoom: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Published index -> the player's media item index, so a remote's "jump" lands on the right song. */
    @Volatile private var published: IntArray = IntArray(0)

    private val hydrator = MirrorHydrator(ExoTarget(), stillMirroring = { manager.isMirroring })

    /** The queue and order last given to [QueueShuffle] for the mirror, so a heartbeat does not redo it. */
    private var adoptedShuffle: Pair<List<String>, List<String>>? = null

    fun start() {
        ConnectSongs.load(context)
        jobs += scope.launch {
            AppSettings.connectEnabled.collect { reconcile() }
        }
        jobs += scope.launch {
            // Signing in or out, or switching account, changes the room without any event to hear it.
            while (true) {
                delay(ACCOUNT_POLL_MS)
                reconcile()
            }
        }
        jobs += scope.launch { mirror() }
        jobs += scope.launch { guardOwnVolume() }
        jobs += scope.launch {
            // Turning shuffle on with nothing left to reorder changes no timeline: the flag itself is the event.
            QueueShuffle.enabled.drop(1).collect { manager.onLocalChanged() }
        }
        jobs += scope.launch {
            // Anything that makes this phone play for itself ends the mirror: a party, a Cast or watch speaker.
            combine(
                ListenTogether.state.map { it.inParty }.distinctUntilChanged(),
                CastController.state.map { it.casting }.distinctUntilChanged(),
                WearBridge.watchOutput.map { it.active }.distinctUntilChanged(),
            ) { party, casting, watch -> party || casting || watch }
                .collect { own -> if (own) manager.leaveMirror() }
        }
        speaker.onLocalStarted = ::onLocalStartedWhileMirroring
        registerNetwork()
    }

    fun stop() {
        speaker.onLocalStarted = {}
        jobs.forEach(Job::cancel)
        jobs.clear()
        mirrorWas = false
        sessionWas = null
        unregisterNetwork()
        manager.stop()
        joinedRoom = null
    }

    /**
     * The phone's media volume is its own: handing playback over from another device must not raise it
     * (see [ConnectVolumeGuard]). Watches the music stream and undoes a jump to maximum after a hand-over.
     */
    private suspend fun guardOwnVolume() {
        val guard = ConnectVolumeGuard()
        val am = audio ?: return
        while (true) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val restore = guard.tick(SystemClock.elapsedRealtime(), manager.isMirroring || manager.isRemote, current, max)
            if (restore != null) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0) }
            delay(VOLUME_GUARD_POLL_MS)
        }
    }

    /** Joins, leaves or moves between rooms to match the setting and the signed-in account. */
    private fun reconcile() {
        val room = if (AppSettings.connectEnabled.value) ListenTogether.accountRoomId() else null
        if (room == joinedRoom) return
        joinedRoom = room
        if (room == null) {
            manager.stop()
            return
        }
        manager.start(
            room = room,
            device = ConnectDevice(
                deviceId = ListenTogether.installDeviceId().take(ConnectLimits.MAX_VIDEO_ID),
                name = deviceName(),
                platform = BuildConfig.CONNECT_PLATFORM,
            ),
            local = this,
        )
    }

    private fun deviceName(): String =
        (Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android").take(ConnectLimits.MAX_DEVICE_NAME)

    private fun registerNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Do not wait out the backoff: the network that just came back is what the socket was waiting for.
                scope.launch { manager.nudge() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "network callback unavailable") }
    }

    private fun unregisterNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    // ---- Outbound ---------------------------------------------------------------------------

    /** The player reported something; the active device passes it on, anyone else ignores it. */
    fun onPlayerEvents(events: Player.Events) {
        if (events.containsAny(*RELEVANT_EVENTS)) manager.onLocalChanged()
    }

    /** The user did something to the player (the session's door): maybe "start playback here". */
    fun onUserIntent() {
        // A mirrored control is a command for the other device, not a claim for this one.
        if (manager.isMirroring) return
        manager.onLocalIntent()
    }

    // ---- Mirror mode ------------------------------------------------------------------------

    private var mirrorWas = false
    private var sessionWas: RemoteSession? = null

    /**
     * Follows the session while this phone mirrors it: on entering, the player goes quiet; while in, its queue is
     * brought to the session's; the session player is told what to re-read. One collector, so the order of the
     * three is always the same.
     */
    private suspend fun mirror() {
        combine(manager.mirroring, manager.remote) { mirroring, session -> mirroring to session }
            .collectLatest { (mirroring, session) ->
                val was = mirrorWas
                val before = sessionWas
                if (mirroring && !was) enterMirror()
                if (mirroring && session != null) {
                    hydrator.apply(session, manager.remotePlayheadMs())
                    // Each song's own record, asked for here: what is drawn does not depend on what the sender attached.
                    ConnectSongs.resolveMissing(scope, session.state.queue.map { it.videoId }, ::refreshRows)
                }
                if (!mirroring && was) exitMirror()
                mirrorWas = mirroring
                sessionWas = session
                speaker.announce(before, session, was, mirroring)
            }
    }

    /** A song's own record arrived: the rows of the idle player that hold it are drawn from it from now on. */
    private fun refreshRows(videoId: String) {
        if (!manager.isMirroring) return
        val exo = player() ?: return
        val song = ConnectSongs.cached(videoId) ?: return
        for (i in 0 until exo.mediaItemCount) {
            val row = exo.getMediaItemAt(i)
            if (row.mediaId != videoId) continue
            // Keeps what the sender told about this row (its length), takes the phone's own cover and pages.
            val length = row.toSong().durationText
            exo.replaceMediaItem(i, song.copy(durationText = song.durationText ?: length).toMediaItem())
        }
    }

    /** The phone just became a mirror: whatever it was playing stops (a release already paused it) and nothing is read ahead. */
    private fun enterMirror() {
        adoptedShuffle = null
        val exo = player() ?: return
        exo.playWhenReady = false
        // Idle, with the queue and position kept: no stream is open, none is fetched, no focus is held.
        if (exo.playbackState != Player.STATE_IDLE) exo.stop()
        if (DjSession.state.value.active) scope.launch { DjSession.leave() }
    }

    /**
     * The mirror ended. When the session itself is gone (nobody is the active device, nor is this one) the phone
     * keeps what it shows, paused at the last playhead, as an ordinary queue; otherwise the caller (a takeover, a new
     * queue) is about to load its own.
     */
    private fun exitMirror() {
        if (manager.remote.value != null || manager.isActive) return
        val exo = player() ?: return
        if (exo.mediaItemCount > 0 && exo.currentMediaItemIndex in 0 until exo.mediaItemCount) {
            exo.seekTo(exo.currentMediaItemIndex, hydrator.lastHeadMs)
        }
    }

    /** The idle player was started by something inside the service while it mirrors. */
    private fun onLocalStartedWhileMirroring(exo: Player) {
        if (exo.playbackState == Player.STATE_IDLE) {
            // Nothing could sound (it is not prepared): put the flag back so it is not a half-started player.
            exo.playWhenReady = false
            return
        }
        // It really would play: that is this phone starting playback, so the mirror ends and the phone claims.
        manager.leaveMirror()
        manager.onLocalIntent()
    }

    /** The idle ExoPlayer as [MirrorHydrator] writes to it. Everything goes straight to the ExoPlayer, below the session wrapper. */
    private inner class ExoTarget : MirrorTarget {
        override fun queueIds(): List<String> {
            val exo = player() ?: return emptyList()
            return List(exo.mediaItemCount) { exo.getMediaItemAt(it).mediaId }
        }

        override fun currentIndex(): Int = player()?.currentMediaItemIndex ?: -1

        override suspend fun replace(edit: QueueEdit) {
            val items = withContext(Dispatchers.Default) { edit.insert.map { it.toSong().toMediaItem() } }
            val exo = player() ?: return
            val count = exo.mediaItemCount
            val from = edit.from.coerceIn(0, count)
            val to = edit.to.coerceIn(from, count)
            exo.replaceMediaItems(from, to, items)
        }

        override fun clear() {
            player()?.clearMediaItems()
            adoptedShuffle = null
        }

        override fun seekTo(index: Int, positionMs: Long) {
            val exo = player() ?: return
            if (index in 0 until exo.mediaItemCount) exo.seekTo(index, positionMs.coerceAtLeast(0L))
        }

        override fun setRepeat(repeat: ConnectRepeat) {
            val exo = player() ?: return
            val mode = repeat.toPlayerMode()
            if (exo.repeatMode != mode) exo.repeatMode = mode
        }

        override fun setShuffle(on: Boolean, order: List<String>?) {
            val exo = player() ?: return
            if (!on) {
                if (QueueShuffle.enabled.value) QueueShuffle.adoptUnshuffled()
                adoptedShuffle = null
                return
            }
            val ids = queueIds()
            val key = ids to (order ?: emptyList())
            if (QueueShuffle.enabled.value && adoptedShuffle == key) return
            QueueShuffle.adoptShared(List(exo.mediaItemCount) { exo.getMediaItemAt(it) }, order)
            adoptedShuffle = key
        }
    }

    // ---- ConnectLocal -----------------------------------------------------------------------

    override fun snapshot(): PlaybackState? {
        val exo = player() ?: return null
        val count = exo.mediaItemCount
        val current = exo.currentMediaItemIndex
        if (count == 0 || current !in 0 until count) return null
        val currentSong = exo.getMediaItemAt(current).toSong()
        if (!currentSong.isShareable()) return null

        val from = maxOf(0, current - ConnectLimits.BEHIND)
        val to = minOf(count, current + ConnectLimits.AHEAD + 1)
        val tracks = ArrayList<ConnectTrack>(to - from)
        val map = ArrayList<Int>(to - from)
        var index = -1
        for (i in from until to) {
            val song = if (i == current) currentSong else exo.getMediaItemAt(i).toSong()
            if (!song.isShareable()) continue
            if (i == current) index = tracks.size
            tracks += song.toConnectTrack(if (i == current) exo.duration else null)
            map += i
        }
        if (index < 0) return null
        published = map.toIntArray()
        val order = QueueShuffle.sharedOrder(exo, tracks.map { it.videoId })
        val playing = exo.isPlaying || (exo.playWhenReady &&
            exo.playbackState != Player.STATE_IDLE && exo.playbackState != Player.STATE_ENDED)
        return PlaybackState(
            queue = tracks,
            index = index,
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            isPlaying = playing,
            volume = volume(),
            shuffle = QueueShuffle.enabled.value,
            repeat = when (exo.repeatMode) {
                Player.REPEAT_MODE_ALL -> ConnectRepeat.ALL
                Player.REPEAT_MODE_ONE -> ConnectRepeat.ONE
                else -> ConnectRepeat.OFF
            },
            order = order,
        )
    }

    override fun isPartyQueue(): Boolean = ListenTogether.state.value.inParty

    override fun volume(): Double {
        val am = audio ?: return 1.0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max else 1.0
    }

    private fun setVolume(level: Double) {
        val am = audio ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0.0, 1.0) * max).roundToInt(), 0)
        }
    }

    override fun takeover(state: PlaybackState, play: Boolean, done: () -> Unit) {
        scope.launch {
            // Off the main thread: building an item resolves artwork sizes and asks Downloads whether each
            // track is on disk (see PartySync.load, which does the same).
            val items = withContext(Dispatchers.Default) { state.queue.map { it.toSong().toMediaItem() } }
            val exo = player()
            if (exo == null) {
                done()
                return@launch
            }
            exo.setMediaItems(items, state.index, state.positionMs)
            exo.repeatMode = state.repeat.toPlayerMode()
            // The queue arrived in its sender's order already. The order it had before the shuffle comes with it,
            // so turning shuffle off here restores the very list every other device shows.
            if (state.shuffle) QueueShuffle.adoptShared(items, state.order) else QueueShuffle.adoptUnshuffled()
            adoptedShuffle = null
            // The volume stays this device's own: the sender's level (the web's is 1.0 by default) is not copied,
            // it would set the phone's media volume to maximum. A remote moves it only by a volume command.
            exo.prepare()
            exo.playWhenReady = play
            done()
        }
    }

    override fun pause() {
        // Straight to the ExoPlayer, beneath the session's wrapper: a pause that is not the user's must not be seen as theirs.
        player()?.let { it.playWhenReady = false }
    }

    override fun applyCommand(command: Command) {
        val door = commandDoor() ?: player() ?: return
        when (command.action) {
            CommandAction.PLAY -> door.play()
            CommandAction.PAUSE -> door.pause()
            CommandAction.TOGGLE -> if (door.playWhenReady) door.pause() else door.play()
            CommandAction.NEXT -> door.seekToNextMediaItem()
            CommandAction.PREVIOUS -> door.seekToPreviousMediaItem()
            CommandAction.SEEK -> command.number?.let { door.seekTo(it.toLong().coerceAtLeast(0L)) }
            CommandAction.VOLUME -> command.number?.let(::setVolume)
            CommandAction.SHUFFLE -> command.flag?.let { want ->
                val exo = player()
                if (exo != null && want != QueueShuffle.enabled.value) QueueShuffle.toggle(exo)
            }
            CommandAction.REPEAT -> command.repeatMode?.let { mode -> player()?.repeatMode = mode.toPlayerMode() }
            CommandAction.JUMP -> command.index?.let { at ->
                val target = published.getOrNull(at)
                if (target != null && target in 0 until door.mediaItemCount) door.seekTo(target, 0L)
            }
            CommandAction.PLAY_NEXT -> command.tracks?.let { addTracks(it, next = true) }
            CommandAction.ADD_TO_QUEUE -> command.tracks?.let { addTracks(it, next = false) }
            CommandAction.REMOVE -> editQueue { exo ->
                command.index?.let(::localIndex)?.let { exo.removeMediaItem(it) }
            }
            CommandAction.MOVE -> editQueue { exo ->
                val from = command.index?.let(::localIndex)
                val to = command.to?.let(::localIndex)
                if (from != null && to != null && from != to) exo.moveMediaItem(from, to)
            }
            CommandAction.CLEAR_UPCOMING -> editQueue { exo ->
                val first = exo.currentMediaItemIndex + 1
                if (first < exo.mediaItemCount) exo.removeMediaItems(first, exo.mediaItemCount)
            }
        }
    }

    /** The player's row for the published row [at], or null when that row is not (or no longer) there. */
    private fun localIndex(at: Int): Int? = published.getOrNull(at)

    /**
     * A queue edit a remote asked for, done straight on the ExoPlayer (below the session wrapper: it is not the
     * phone's user, and it must not reach a party). A party's queue is the server's and is left alone. The edit
     * fires the timeline event that publishes the new queue.
     */
    private fun editQueue(edit: (Player) -> Unit) {
        if (isPartyQueue()) return
        val exo = player() ?: return
        edit(exo)
        manager.onLocalChanged()
    }

    /** "Play next" goes right behind the playing song, "add to queue" behind what was queued (as on this phone). */
    private fun addTracks(tracks: List<ConnectTrack>, next: Boolean) = editQueue { exo ->
        val songs = tracks.take(ConnectLimits.MAX_QUEUE).map { it.toSong() }
        if (songs.isEmpty()) return@editQueue
        val current = exo.currentMediaItemIndex
        val at = if (next) {
            (current + 1).coerceIn(0, exo.mediaItemCount)
        } else {
            var i = (current + 1).coerceIn(0, exo.mediaItemCount)
            while (i < exo.mediaItemCount && exo.getMediaItemAt(i).queueTier == QueueTier.USER_QUEUE) i++
            i
        }
        val items = songs.map { with(QueueCoordinator) { it.asQueueEntry(QueueTier.USER_QUEUE) }.toMediaItem() }
        exo.addMediaItems(at, items)
    }

    companion object {
        private const val TAG = "ConnectSync"
        private const val VOLUME_GUARD_POLL_MS = 200L
        private const val ACCOUNT_POLL_MS = 5_000L

        private val RELEVANT_EVENTS = intArrayOf(
            Player.EVENT_IS_PLAYING_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_REPEAT_MODE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        )
    }
}

/** The rows of [this] another device can load: what the mirror may send as a queue edit. */
internal fun List<MediaItem>.toShareableTracks(): List<ConnectTrack> =
    mapNotNull { it.toSong().takeIf(Song::isShareable)?.toConnectTrack(null) }

internal fun ConnectRepeat.toPlayerMode(): Int = when (this) {
    ConnectRepeat.ALL -> Player.REPEAT_MODE_ALL
    ConnectRepeat.ONE -> Player.REPEAT_MODE_ONE
    ConnectRepeat.OFF -> Player.REPEAT_MODE_OFF
}

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Whether another device can load this song from YouTube by its id. A local file, a track of another source
 * (those carry a prefixed key, not a bare id) and the DJ's clips cannot.
 */
internal fun Song.isShareable(): Boolean = !isDjClip && YOUTUBE_ID.matches(videoId)

internal fun Song.toConnectTrack(playerDurationMs: Long?): ConnectTrack = ConnectTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    album = albumName?.takeIf { it.isNotBlank() },
    artworkUrl = thumbnailUrl?.takeIf { it.startsWith("http") },
    durationSec = playerDurationMs?.takeIf { it > 0L }?.let { (it / 1000).toInt() }
        ?: TrackMatcher.secondsOf(durationText),
)

internal fun ConnectTrack.toSong(): Song {
    // What the phone already knows of the song wins over what the sender put in the queue: its own cover, album
    // and artist pages. The sender's fields stand in until the phone has asked for them (see [ConnectSongs]).
    val own = ConnectSongs.cached(videoId)
    return Song(
        videoId = videoId,
        title = own?.title?.takeIf { it.isNotBlank() } ?: title,
        artist = own?.artist?.takeIf { it.isNotBlank() } ?: artist,
        thumbnailUrl = own?.thumbnailUrl ?: artworkUrl,
        // What a cross-source match is made on, as in PartyTrack.toSong.
        durationText = durationSec?.let { "%d:%02d".format(it / 60, it % 60) } ?: own?.durationText,
        albumName = own?.albumName ?: album,
        artistId = own?.artistId,
        artists = own?.artists.orEmpty(),
        albumId = own?.albumId,
        queueTier = QueueTier.CONTEXT,
    )
}
