package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.bitchord.data.connect.Command
import com.music.bitchord.data.connect.CommandAction
import com.music.bitchord.data.connect.ConnectManager
import com.music.bitchord.data.connect.ConnectRepeat
import com.music.bitchord.data.connect.ConnectTrack
import com.music.bitchord.data.connect.MirrorPlan
import com.music.bitchord.data.connect.QueueEdit
import com.music.bitchord.data.connect.RemoteSession
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import kotlin.math.roundToInt

/*
 * Mirror mode: while another Auden device is the one playing, this phone's player shows the same session and
 * makes no sound.
 *
 * The model is the one Cast and the watch already use (see RemoteSpeaker): the phone's ExoPlayer owns the QUEUE
 * (kept, in step with the active device's, and never prepared, so no audio stream is ever fetched) and a speaker
 * owns the CLOCK. The session player answers with the speaker's play state, position, duration and volume in
 * place of the idle ExoPlayer's, so the app, notification, lock screen, widgets, Auto and the watch all show the
 * remote session. Everything the user does to those surfaces reaches the session player first and is sent on as
 * a Connect command ([MirrorDoor]) instead of being done to the idle ExoPlayer.
 */

/** Every side effect of a player that is really playing on this phone, and so off while it only mirrors. */
enum class Effect {
    /** Last.fm / ListenBrainz scrobbles, "now playing", listening history, the listening record, Discord. */
    LISTEN_BOOKKEEPING,

    /** Per-track work for the song that became current: loudness, caches, read-ahead, a second look for a copy. */
    TRACK_START,

    /** AutoPlay top-ups, the repeat-all stash and bounded-context top-ups: all of them grow the queue. */
    QUEUE_GROWTH,

    /** Direct Source Stream's read-ahead window and anything else that opens a stream ahead of playback. */
    STREAM_FETCH,

    /** Writing the queue as the "last played" snapshot a cold start restores. */
    QUEUE_SNAPSHOT,
}

/** Asked by the service before each of [Effect]; blocks them all while [mirroring]. */
class MirrorGate(private val mirroring: () -> Boolean) {
    fun blocks(effect: Effect): Boolean = when (effect) {
        Effect.LISTEN_BOOKKEEPING,
        Effect.TRACK_START,
        Effect.QUEUE_GROWTH,
        Effect.STREAM_FETCH,
        Effect.QUEUE_SNAPSHOT,
        -> mirroring()
    }

    val active: Boolean get() = mirroring()

    companion object {
        val OFF = MirrorGate { false }
    }
}

/**
 * What the session player asks of mirror mode. [active] is checked first by every override; when it is true the
 * call is answered here (a Connect command) and never reaches the idle ExoPlayer.
 */
interface MirrorDoor {
    val active: Boolean

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBack(incrementMs: Long)
    fun seekForward(incrementMs: Long)
    fun next()
    fun previous()
    fun jump(index: Int)
    fun repeat(mode: Int)
    fun shuffle(on: Boolean)

    /** Songs inserted at [index] of a queue whose playing song is [currentIndex]. */
    fun add(index: Int, tracks: List<ConnectTrack>, currentIndex: Int)

    /** Rows [from, to) removed from a queue of [count] rows whose playing song is [currentIndex]. */
    fun remove(from: Int, to: Int, currentIndex: Int, count: Int)
    fun move(from: Int, to: Int)

    /** The user starts something of their own on this phone: the mirror ends and the phone is its own player. */
    fun newSession()
}

/** The door when Connect is not there at all. */
object NoMirror : MirrorDoor {
    override val active = false
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBack(incrementMs: Long) = Unit
    override fun seekForward(incrementMs: Long) = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun jump(index: Int) = Unit
    override fun repeat(mode: Int) = Unit
    override fun shuffle(on: Boolean) = Unit
    override fun add(index: Int, tracks: List<ConnectTrack>, currentIndex: Int) = Unit
    override fun remove(from: Int, to: Int, currentIndex: Int, count: Int) = Unit
    override fun move(from: Int, to: Int) = Unit
    override fun newSession() = Unit
}

/** Turns what the user did to the mirrored player into Connect commands for the active device. */
class MirrorRouting(private val manager: ConnectManager) : MirrorDoor {
    override val active: Boolean get() = manager.isMirroring

    private fun send(command: Command) {
        manager.remoteCommand(command)
    }

    override fun play() = send(Command.of(CommandAction.PLAY))
    override fun pause() = send(Command.of(CommandAction.PAUSE))
    override fun seekTo(positionMs: Long) = send(Command.seek(positionMs.coerceAtLeast(0L)))

    override fun seekBack(incrementMs: Long) = send(Command.seek((manager.remotePlayheadMs() - incrementMs).coerceAtLeast(0L)))

    override fun seekForward(incrementMs: Long) {
        val duration = manager.remote.value?.state?.current?.durationSec?.let { it * 1000L }
        val to = manager.remotePlayheadMs() + incrementMs
        send(Command.seek(if (duration != null && duration > 0) minOf(to, duration) else to))
    }

    override fun next() = send(Command.of(CommandAction.NEXT))
    override fun previous() = send(Command.of(CommandAction.PREVIOUS))
    override fun jump(index: Int) = send(Command.jump(index))

    override fun repeat(mode: Int) = send(
        Command.repeat(
            when (mode) {
                Player.REPEAT_MODE_ALL -> ConnectRepeat.ALL
                Player.REPEAT_MODE_ONE -> ConnectRepeat.ONE
                else -> ConnectRepeat.OFF
            },
        ),
    )

    override fun shuffle(on: Boolean) = send(Command.shuffle(on))

    override fun add(index: Int, tracks: List<ConnectTrack>, currentIndex: Int) {
        if (tracks.isEmpty()) return
        // "Play next" lands right behind the playing song; "add to queue" further back, behind what was queued.
        send(if (index <= currentIndex + 1) Command.playNext(tracks) else Command.addToQueue(tracks))
    }

    override fun remove(from: Int, to: Int, currentIndex: Int, count: Int) {
        if (to <= from) return
        if (to - from > 1 && from == currentIndex + 1 && to >= count) {
            send(Command.clearUpcoming())
            return
        }
        // Last first, so the indices still ahead of each removal hold.
        for (i in to - 1 downTo from) send(Command.remove(i))
    }

    override fun move(from: Int, to: Int) {
        if (from != to) send(Command.move(from, to))
    }

    override fun newSession() = manager.leaveMirror()
}

/**
 * The speaker of the mirror: the active device's session, as the session player reports it. The numbers come from
 * the manager's remote session and its server-clock playhead, so every surface reads the same moment.
 */
class ConnectSpeaker(private val manager: ConnectManager) : RemoteSpeaker {
    override var sink: CastSink? = null

    private val session: RemoteSession? get() = manager.remote.value

    override val active: Boolean get() = manager.isMirroring

    override val playWhenReady: Boolean get() = active && session?.state?.isPlaying == true

    override val isPlaying: Boolean get() = playWhenReady

    override val playbackState: Int get() = if (active) Player.STATE_READY else Player.STATE_IDLE

    override val positionMs: Long get() = if (active) manager.remotePlayheadMs() else 0L

    override val durationMs: Long
        get() = session?.state?.current?.durationSec?.takeIf { it > 0 }?.let { it * 1000L } ?: C.TIME_UNSET

    override val deviceVolume: Int get() = ((session?.state?.volume ?: 0.0) * 100).roundToInt().coerceIn(0, 100)

    override fun play() {
        manager.remoteCommand(Command.of(CommandAction.PLAY))
    }

    override fun pause() {
        manager.remoteCommand(Command.of(CommandAction.PAUSE))
    }

    override fun seekTo(positionMs: Long) {
        manager.remoteCommand(Command.seek(positionMs.coerceAtLeast(0L)))
    }

    override fun setVolume(fraction: Float) {
        manager.remoteCommand(Command.volume(fraction.toDouble().coerceIn(0.0, 1.0)))
    }

    // The queue is kept in step by [MirrorHydrator], from the session; the phone's own moves are not the speaker's.
    override fun onLocalTransition(item: androidx.media3.common.MediaItem?, reason: Int) = Unit
    override fun onLocalQueueChanged() = Unit
    override fun onLocalRepeatChanged(repeatMode: Int) = Unit

    /** Something started the phone's own player while it mirrors: the service decides (see [ConnectSync]). */
    var onLocalStarted: (Player) -> Unit = {}

    override fun onLocalStartedPlaying(player: Player) {
        if (active) onLocalStarted(player)
    }

    /**
     * The session (or the mirror itself) changed: tell the session player what it has to re-read. A new play
     * state, position or song re-anchors every controller; the mirror starting or ending changes the device the
     * volume keys move; a new volume moves the bar.
     */
    fun announce(before: RemoteSession?, after: RemoteSession?, wasActive: Boolean, isActive: Boolean) {
        val sink = sink ?: return
        if (wasActive != isActive) sink.onRemoteRouteChanged()
        if (!isActive) return
        val volumeBefore = before?.state?.volume
        val volumeAfter = after?.state?.volume
        sink.onRemotePlaybackChanged()
        if (wasActive != isActive || volumeBefore != volumeAfter) sink.onRemoteVolumeChanged()
    }
}

/** What [MirrorHydrator] writes to: the phone's idle player, or a fake in a test. */
interface MirrorTarget {
    /** The videoIds of the queue now held, in order. */
    fun queueIds(): List<String>
    fun currentIndex(): Int

    /** Replaces the rows [QueueEdit.from] until [QueueEdit.to] with [QueueEdit.insert], built off the main thread. */
    suspend fun replace(edit: QueueEdit)
    fun clear()
    fun seekTo(index: Int, positionMs: Long)
    fun setRepeat(repeat: ConnectRepeat)

    /** The shuffle flag of the session, with the unshuffled order when it was sent, for the queue now held. */
    fun setShuffle(on: Boolean, order: List<String>?)
}

/**
 * Keeps the phone's queue equal to the active device's, with the smallest possible edit, and never starts it:
 * the player only ever holds the rows, so no audio is requested. Called with every new session while mirroring.
 */
class MirrorHydrator(private val target: MirrorTarget, private val stillMirroring: () -> Boolean) {

    /** The last playhead written, for the player to start from if the mirror ends (see [lastHeadMs]). */
    var lastHeadMs: Long = 0L
        private set

    suspend fun apply(session: RemoteSession, headMs: Long) {
        if (!stillMirroring()) return
        val state = session.state
        lastHeadMs = headMs
        if (state.queue.isEmpty()) {
            if (target.queueIds().isNotEmpty()) target.clear()
            return
        }
        val edit = MirrorPlan.diff(target.queueIds(), state.queue)
        if (edit != null) {
            target.replace(edit)
            // A long build can outlive the mirror (the user started something of their own meanwhile).
            if (!stillMirroring()) return
        }
        if (MirrorPlan.needsSeek(target.currentIndex(), state)) target.seekTo(state.index, headMs)
        target.setRepeat(state.repeat)
        target.setShuffle(state.shuffle, state.order)
    }
}
