package dev.auden.dj

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.model.DJ_CLIP_PREFIX
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toggleAutoplayAndWait
import com.music.bitchord.playback.toggleShuffleAndWait
import dev.auden.dj.voice.DjClip
import dev.auden.dj.voice.DjClipRenderer
import dev.auden.dj.voice.FishVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import kotlin.random.Random

/** What the DJ's sheet and the player show about the session. */
data class DjUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    /**
     * Something was asked of the DJ and what it asked for has not started playing yet: the start of a
     * session, or a change of what it plays. The DJ's loading screen shows while this is true. It
     * always ends: when the asked-for set starts, when the ask fails or is stopped, or after
     * [PENDING_TIMEOUT_MS] whatever happened.
     */
    val pending: Boolean = false,
    /** The words of the latest ask; empty for a surprise. */
    val request: String = "",
    /** What the current block is about, in words ("Radiohead", "Tranquilo", "Los 90"). */
    val theme: String? = null,
    /** Whether the DJ can speak in its language right now, and with whom. */
    val voice: Voice = Voice.Unavailable,
    /** Why the last start did not happen, if it did not. */
    val problem: Problem? = null,
    /** Why a line could not be spoken, when one could not: shown, so it can be told and fixed. */
    val speechError: String? = null,
) {
    /** The DJ is on, or on its way: what the player's DJ mode follows. */
    val active: Boolean get() = pending || running

    sealed interface Voice {
        /** No key, or no voice for the language: the DJ plays, and the words are on screen. */
        data object Unavailable : Voice
        /** The DJ speaks, with the voice called [name]. */
        data class Ready(val name: String) : Voice
    }

    enum class Problem { TOO_FEW_SONGS, NO_PLAYER }
}

/** How long the loading screen waits for an ask at most, before it gives way whatever happened. */
const val PENDING_TIMEOUT_MS = 30_000L

/** How many songs a search for the listener's ask is given to make sets from. */
private const val SEARCH_SONGS = 20

/** How long a queue change is given to show in the controller before the player is moved by position. */
private const val QUEUE_SYNC_MS = 3_000L

/**
 * One DJ session: themed sets of songs, each introduced by something the DJ says.
 *
 * The DJ is queued like music. A line is rendered into a clip and placed in the queue before the
 * set it introduces, so the player moves from the last song of a set to the DJ, and from the DJ to
 * the first song of the next set, as it would between any two tracks. Sets are planned one ahead:
 * when a set starts, the one after it is chosen, its line is rendered, and both are queued, so the
 * queue is never empty and the voice is never waiting for a song to end. The voice is a network
 * call that takes seconds, so the start waits for one line only, the opening: the second set is
 * planned once the first is playing, like every set after it.
 *
 * The session lives in the process, not in a screen, and drives the player through its own
 * controller: it goes on with the phone locked. Every item it queues carries an entry id of its
 * own, which is how it tells its items from the listener's. A song the listener slips in (Play
 * next, Add to queue) is let be, and the DJ carries on around it; a queue with nothing of the DJ's
 * left in it was taken somewhere else, and the session steps aside rather than fighting for it.
 * A screen about to play something of its own calls [leave] first and waits for it, so shuffle,
 * repeat and AutoPlay are back as the listener had them before it reads them.
 *
 * Everything here is called on the main thread, and the session's state is only touched there.
 * The planner runs off it, one call at a time and in the order asked; the voice and the library
 * load on the IO threads; their results come back to the main thread before anything is changed.
 */
object DjSession {
    private const val TAG = "AudenDj"

    // A failure in a set planned in the background is the DJ's, not the music's: it is logged, the
    // loading screen ends on its own timeout, and the player plays on.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(TAG, "DJ task failed", error) },
    )
    private val _state = MutableStateFlow(DjUiState())
    val state: StateFlow<DjUiState> = _state.asStateFlow()

    /** The session the DJ is running or starting; null when it is off. */
    private var live: Run? = null

    /**
     * The hand-back of the session that ended last, while it runs: a new start, and [leave], wait
     * for it, so nothing reads shuffle or AutoPlay before they are back.
     */
    private var closing: Job? = null

    /** The latest ask made while the session was still starting: applied the moment it has. */
    private var queuedRequest: String? = null

    private var pendingTimeout: Job? = null

    /** The rendered lines of the running session, by the clip's media id. */
    private val clips = HashMap<String, DjClip>()

    /** The words of a DJ clip on screen while it plays; null for any other song. */
    fun clipOf(mediaId: String?): DjClip? = mediaId?.let { synchronized(clips) { clips[it] } }

    /**
     * Whether [mediaId] is one of the running session's songs (not its voice, not a song the
     * listener added): what More and Less act on. Main thread.
     */
    fun isDjSong(mediaId: String?): Boolean = live?.isSong(mediaId) == true

    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        _state.value = DjUiState(voice = voiceFor(language(app)))
        // The voice packs earlier versions downloaded (about 130 MB) have no code left to read them.
        scope.launch(Dispatchers.IO) { java.io.File(app.filesDir, "dj/voices").deleteRecursively() }
        // The language is a setting: the voice the state names follows it, whether or not a session is on.
        scope.launch(Dispatchers.Main) { AppSettings.djLanguage.collect { refreshVoice() } }
    }

    private fun refreshVoice() {
        val app = appContext ?: return
        _state.value = _state.value.copy(voice = voiceFor(language(app)))
    }

    /**
     * Starts a session: from a typed [request], or on the DJ's own choice when it is blank. While a
     * start is under way, a second one is not dropped: it becomes a change of what the DJ plays, made
     * the moment the first one is playing.
     */
    fun start(context: Context, request: String?, structured: DjRequest? = null) {
        val text = request?.trim().orEmpty()
        live?.let { if (!it.started) return askOnceStarted(text) }
        stop()
        val app = context.applicationContext
        val session = Run(app, text, structured)
        live = session
        queuedRequest = null
        _state.value = _state.value.copy(
            running = false, starting = true, pending = true, request = text,
            theme = null, problem = null, speechError = null, voice = voiceFor(language(app)),
        )
        holdPending()
        val previous = closing
        session.startJob = scope.launch(Dispatchers.Main) {
            // The last session gives the player back first: what it restores is what this one reads.
            previous?.join()
            try {
                session.begin()
            } catch (error: Throwable) {
                // Stopped or left while starting: the hand-back already has it.
                if (error is CancellationException && session.closed) throw error
                Log.w(TAG, "could not start", error)
                if (live !== session) return@launch
                live = null
                queuedRequest = null
                pendingTimeout?.cancel()
                _state.value = DjUiState(
                    problem = if (error is NotEnoughMusic) DjUiState.Problem.TOO_FEW_SONGS else DjUiState.Problem.NO_PLAYER,
                    voice = voiceFor(language(app)),
                    speechError = _state.value.speechError,
                )
                // It may have got as far as turning shuffle or AutoPlay off: they come back.
                handBack(session, tidyQueue = false)
                return@launch
            }
            if (live !== session || session.closed) return@launch
            val ask = queuedRequest ?: return@launch
            queuedRequest = null
            // Asked again for what is already playing: nothing to change, and nothing more to wait for.
            if (ask == text) settlePending() else session.steer(ask)
        }
    }

    /**
     * Changes what the DJ is asked for, and cuts to it: the set queued after the song playing is
     * replaced by one that follows the new request. A blank request is a surprise again. With no
     * session, it starts one; with one still starting, it is kept and made once it is playing.
     */
    fun request(context: Context, text: String) {
        val ask = text.trim()
        val session = live ?: return start(context, ask)
        if (!session.started) return askOnceStarted(ask)
        _state.value = _state.value.copy(pending = true, request = ask)
        holdPending()
        session.steer(ask)
    }

    private fun askOnceStarted(text: String) {
        queuedRequest = text
        _state.value = _state.value.copy(pending = true, request = text)
        holdPending()
    }

    /**
     * Ends the session, and leaves the queue where it is: its songs play on, without the DJ's
     * voice. The player is handed back in the background, so this returns at once; a screen about
     * to play something else calls [leave] instead.
     */
    fun stop() {
        val session = endState() ?: return
        handBack(session, tidyQueue = true)
    }

    /**
     * Ends the session for a screen about to replace the queue with something of its own, and
     * returns once the player is the listener's again: shuffle, repeat and AutoPlay as they had
     * them, the DJ's controller let go, a start still under way called off. Called in the coroutine
     * that is about to play, before it reads [QueueShuffle.enabled]. Returns at once when the DJ is off.
     *
     * The queue is left alone (the caller is about to replace it): the clips it still holds are
     * deleted a little later, once nothing can be playing them.
     */
    suspend fun leave() {
        withContext(Dispatchers.Main.immediate) {
            val session = endState()
            if (session == null) {
                // A step aside or a Stop may still be handing back: it is waited for all the same.
                closing?.takeIf { it.isActive }?.join()
                return@withContext
            }
            handBack(session, tidyQueue = false).join()
        }
    }

    /** Marks the session over, at once, and the state with it; the session, if there was one. */
    private fun endState(): Run? {
        pendingTimeout?.cancel()
        queuedRequest = null
        val session = live
        live = null
        session?.markClosed()
        _state.value = _state.value.copy(
            running = false, starting = false, pending = false, request = "", theme = null, problem = null,
        )
        refreshVoice()
        return session
    }

    /** Hands the player back from [session], after whatever hand-back is already running. */
    private fun handBack(session: Run, tidyQueue: Boolean): Job {
        val previous = closing
        return scope.launch(Dispatchers.Main) {
            previous?.join()
            session.close(tidyQueue)
        }.also { closing = it }
    }

    private fun holdPending() {
        pendingTimeout?.cancel()
        pendingTimeout = scope.launch(Dispatchers.Main) {
            delay(PENDING_TIMEOUT_MS)
            settlePending()
        }
    }

    private fun settlePending() {
        pendingTimeout?.cancel()
        pendingTimeout = null
        if (_state.value.pending) _state.value = _state.value.copy(pending = false)
    }

    /** More like the song playing now. */
    fun more() { live?.more() }

    /** Less like the song playing now: its artist leaves the session, and the set moves on. */
    fun less() { live?.less() }

    /** The language the DJ speaks and writes in: the listener's choice, English or Spanish. */
    private fun language(@Suppress("UNUSED_PARAMETER") context: Context): String = AppSettings.djLanguage.value

    /** Where the voice stands for [language]: unavailable (no key, or not a language it speaks), or ready. */
    private fun voiceFor(language: String): DjUiState.Voice {
        val voice = FishVoice.voiceFor(language) ?: return DjUiState.Voice.Unavailable
        return if (BuildConfig.FISH_API_KEY.isNotBlank()) DjUiState.Voice.Ready(voice.name) else DjUiState.Voice.Unavailable
    }

    /**
     * One running session. Its state lives on the main thread, where every player call is made; the
     * planner is confined to [planning]. The DJ's own items are kept as [order], one id per item in
     * the order queued (a song's video id, a clip's media id), and [place] says which set each id
     * belongs to and where in it. A set is a clip (its line, when there is a voice for it) followed by
     * its songs. The player's queue may hold the listener's songs between them: an index into the
     * player is always found by looking, never assumed from [order].
     */
    private class Run(private val app: Context, requestText: String, private val structured: DjRequest? = null) {
        private val lang = language(app)
        private val blockSize = AppSettings.djBlockSize.value
        // Asked each time, not once: a setting changed while the DJ is on takes effect at the next line.
        private fun speakable() = AppSettings.djVoice.value &&
            BuildConfig.FISH_API_KEY.isNotBlank() && FishVoice.voiceFor(lang) != null &&
            DjPhrasesCheck.supports(app, lang)
        private fun chatty() = AppSettings.djChatty.value && speakable()
        private val now = System.currentTimeMillis()

        /** One call at a time, in the order asked: the planner is not thread-safe, and its work is not for the main thread. */
        private val planning = Dispatchers.Default.limitedParallelism(1)

        private val songs = HashMap<String, Song>()
        private val tracks = HashMap<String, DjTrack>()
        private val order = ArrayList<String>()
        private val place = HashMap<String, Pair<Int, Int>>()
        /** Our ids by the entry id their queue item carries, and back. */
        private val entries = HashMap<String, String>()
        private val entryOf = HashMap<String, String>()
        private var entrySeq = 0
        private val entryPrefix = "auden-dj:${now.toString(36)}:"
        private val sets = ArrayList<DjBlock>()
        /** The sets whose next set has been planned (or is being), so it is planned once. */
        private val planned = HashSet<Int>()
        private val noted = HashSet<Int>()
        /** Planning and rendering for the queue: called off when the listener changes what they want. */
        private val queueing = ArrayList<Job>()
        /** Lookups for the whole session (genres, discoveries): only the end of the session stops them. */
        private val background = ArrayList<Job>()
        /**
         * The set whose start ends the wait the listener sees: 0 for the opening, the new set after
         * a change of request, [Int.MAX_VALUE] while that set is still being chosen, -1 once reached.
         */
        private var awaiting = 0
        /**
         * Songs found by searching YouTube Music for what the listener typed, in the order it ranked them,
         * for the sets that follow to be cut from; [foundFor] is the text they answer. Used when the library
         * cannot make sense of the ask, or cannot meet it.
         */
        private var lapStart = SystemClock.elapsedRealtime()
        /** How long since the listener's ask (or the start) reached each stage, in the log: where the wait goes. */
        private fun lap(stage: String) = Log.d(TAG, "lap $stage +${SystemClock.elapsedRealtime() - lapStart}ms")
        private val found = ArrayDeque<DjTrack>()
        private var foundFor: String? = null
        private var foundTaste: String? = null
        private var searched = false
        /** How many sets in a row opened with the DJ's voice. */
        private var spoken = 1
        private var listener: Player.Listener? = null
        var requestText = requestText
            private set
        var startJob: Job? = null
        /** Set the moment the session is over; nothing in flight touches the player or the state after it. */
        var closed = false
            private set
        /** Playing: the controller, planner and writer are there, and asks are steered rather than queued. */
        var started = false
            private set
        private var wasShuffled = false
        private var wasAutoplay = false
        private var wasRepeat = Player.REPEAT_MODE_OFF
        // What this session itself turned off, so that is exactly what is turned back on.
        private var turnedShuffleOff = false
        private var turnedAutoplayOff = false
        private var turnedRepeatOff = false
        private lateinit var controller: MediaController
        private lateinit var planner: DjPlanner
        private lateinit var writer: DjWriter
        private lateinit var renderer: DjClipRenderer

        /** One item the DJ is about to queue: its id, the song the player gets, its place in its set. */
        private class Piece(val id: String, val song: Song, val pos: Int, val clip: DjClip?)

        suspend fun begin() {
            // A clip left behind by an earlier session (the one playing when it was stopped) goes now.
            renderer = withContext(Dispatchers.IO) { DjClipRenderer(app).also { it.clearClips() } }
            ensureOpen()
            val loaded = DjPool.load(app)
            lap("begin pool tracks=${loaded.tracks.size}")
            ensureOpen()
            if (loaded.tracks.size < DjPlanner.MIN_BLOCK) throw NotEnoughMusic()
            tracks.putAll(loaded.tracks.associateBy { it.id })
            songs.putAll(loaded.songs)
            val text = requestText
            var parsed = structured ?: text.takeIf { it.isNotBlank() }?.let { DjRequestParser.parse(it, loaded.tracks) }
            withContext(planning) {
                planner = DjPlanner(loaded.tracks, now, seed = now, blockSize = blockSize, request = parsed, genresOf = ArtistFacts::genresForDj,
                    recently = DjHistory.recent(app), recentOrder = DjHistory.recentOrdered(app),
                    pastThemes = DjHistory.themes(app), pastOpeners = DjHistory.openers(app))
            }
            // A set of discoveries needs them found before the first set is chosen. When the radio gives none
            // (offline, nothing streamed), the ask is let go and the DJ surprises, rather than saying it found nothing.
            parsed?.takeIf { it.discover }?.let { ask ->
                val found = withTimeoutOrNull(DISCOVER_WAIT_MS) { addDiscoveries(loaded.tracks, ask) } == true
                if (!found) {
                    parsed = ask.copy(discover = false, discoverSeed = null, discoverFar = false).takeIf { it.understood }
                    withContext(planning) { planner.setRequest(parsed) }
                }
            }
            val firstPlanned = withContext(planning) { planner.next() }
            val relaxed = planner.relaxed
            lap("begin planned")
            ensureOpen()
            // The Last.fm genres of the artists the listener plays most, asked for once and kept: they
            // give a track its mood. Off the main thread, and the sets already chosen do not wait for it.
            background += scope.launch(Dispatchers.IO) {
                loaded.tracks.groupBy { it.leadArtist }
                    .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
                    .take(GENRE_LOOKUPS)
                    .forEach { ArtistFacts.fetchForDj(it.key) }
            }
            // New to the listener but on the taste they show: the radio of what they know, fetched
            // once here and kept for the session. Surprises only; a request names its songs itself.
            if (parsed == null) background += scope.launch(Dispatchers.Main) { addDiscoveries(loaded.tracks, null) }
            val time = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            val plays = loaded.tracks.groupBy { it.leadArtist.lowercase() }
                .mapValues { (_, ts) -> ts.sumOf { it.plays30 } to ts.sumOf { it.plays } }
            writer = DjWriter(
                AndroidPhrases(app, lang), lang, seed = now,
                hour = time.hour,
                weekend = time.dayOfWeek.value >= 6,
                nowMs = now,
                artistPlays = { plays[it.lowercase()] },
            )

            // Only the opening is waited for. The second set is planned once this one plays, so a
            // start costs one round trip to the voice, not two.
            val first = firstPlanned?.let(::playable)?.takeIf { it.tracks.isNotEmpty() } ?: throw NotEnoughMusic()
            val opening = writer.opening(LocalTime.now().hour, first, parsed, relaxed)
            val openingClip = renderLine(opening, seed = now, budgetMs = WAITED_RENDER_MS)
            lap("begin voiced clip=${openingClip != null}")
            val pieces = draft(first, openingClip)
            sets += first
            // Registered before the session can end, so the hand-back finds this clip and deletes it.
            pieces.forEach { register(it, 0) }
            ensureOpen()

            controller = connect()
            lap("begin connected")
            ensureOpen()
            // The DJ's order is the whole point, and its queue is its own: shuffle, repeat and AutoPlay
            // would reorder it, replay it or append to it, so the lines stop matching the songs. They go
            // off for the session and come back as they were when it ends. Each is waited for, and not
            // given up halfway: what is marked as turned off here is what the hand-back turns back on,
            // so the two must agree even when the session is stopped in the middle of this.
            wasShuffled = QueueShuffle.enabled.value
            wasAutoplay = AppSettings.autoplay.value
            wasRepeat = controller.repeatMode
            withContext<Unit>(NonCancellable) {
                if (wasShuffled) {
                    turnedShuffleOff = true
                    runCatching { controller.toggleShuffleAndWait() }
                }
                if (wasAutoplay) {
                    turnedAutoplayOff = true
                    runCatching { controller.toggleAutoplayAndWait() }
                }
                if (wasRepeat != Player.REPEAT_MODE_OFF) {
                    turnedRepeatOff = true
                    controller.repeatMode = Player.REPEAT_MODE_OFF
                }
            }
            ensureOpen()
            controller.playSongs(pieces.map { it.song }, 0, shuffle = false)
            lap("begin playing")
            ensureOpen()
            listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = entered(mediaItem)
                // A line the player cannot play is not silent: the cause is shown, so it can be fixed.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (controller.currentMediaItem?.mediaId?.startsWith(DJ_CLIP_PREFIX) == true) {
                        Log.w(TAG, "clip failed to play", error)
                        publish { copy(speechError = error.errorCodeName) }
                    }
                }
            }.also(controller::addListener)
            started = true
            publish { copy(running = true, starting = false, theme = label(first.theme), problem = null, voice = voiceFor(lang)) }
            // The first item is current already, so the player will not announce it: it is entered by
            // hand, which also plans the second set.
            entered(controller.currentMediaItem)
        }

        /** The session's own controller, let go of if the start is called off while it connects. */
        private suspend fun connect(): MediaController {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            return try {
                future.await()
            } catch (error: Throwable) {
                MediaController.releaseFuture(future)
                throw error
            }
        }

        /** Writes the state, if this is still the session the state is about. */
        private fun publish(change: DjUiState.() -> DjUiState) {
            if (!closed && live === this) _state.value = _state.value.change()
        }

        /**
         * Called after each step of [begin] that waits: a session ended meanwhile goes no further, and
         * leaves the player and the state to the hand-back. Its job is cancelled as well; this does not
         * count on the cancellation landing at the right step.
         */
        private fun ensureOpen() {
            if (closed) throw CancellationException("the DJ was stopped while starting")
        }

        /**
         * The items that queue [set]: its clip (if any), then its songs. Each gets an entry id of
         * this session's own, which is what tells the DJ's items from the listener's in the queue
         * and survives a version switch, which renames the media id. Nothing is registered yet.
         */
        private fun draft(set: DjBlock, clip: DjClip?): List<Piece> {
            val out = ArrayList<Piece>()
            clip?.let { out += clipPiece(it) }
            set.tracks.forEachIndexed { p, t ->
                songs[t.id]?.let { out += Piece(t.id, it.copy(queueEntryId = nextEntry()), p, null) }
            }
            return out
        }

        private fun clipPiece(clip: DjClip): Piece {
            val id = "$DJ_CLIP_PREFIX${clip.id}"
            val song = Song(
                videoId = id,
                title = app.getString(R.string.dj_title),
                artist = app.getString(R.string.dj_title),
                thumbnailUrl = null,
                durationText = "%d:%02d".format(clip.durationMs / 60_000, clip.durationMs / 1000 % 60),
                localUri = Uri.fromFile(clip.file).toString(),
                playbackSourceType = PlaybackSourceType.QUEUE,
                playbackSource = app.getString(R.string.dj_title),
                queueEntryId = nextEntry(),
            )
            return Piece(id, song, -1, clip)
        }

        private fun nextEntry() = entryPrefix + (++entrySeq)

        /** Records [piece] as queued in [set], at [at] in [order]. Done in the same step as the player call. */
        private fun register(piece: Piece, set: Int, at: Int = order.size) {
            order.add(at, piece.id)
            place[piece.id] = set to piece.pos
            piece.song.queueEntryId?.let { entries[it] = piece.id; entryOf[piece.id] = it }
            piece.clip?.let { c -> synchronized(clips) { clips[piece.id] = c } }
        }

        /** Forgets an item taken out of the queue; a clip's file goes with it, as nothing can reach it now. */
        private fun unregister(id: String) {
            place.remove(id)
            entryOf.remove(id)?.let(entries::remove)
            synchronized(clips) { clips.remove(id) }?.file?.delete()
        }

        /**
         * Which of the DJ's items a queue item at [index] is, or null when it is the listener's. By the
         * entry id first: an item that carries one that is not ours was queued by someone else, even
         * when it is a song the DJ also has. An item that has lost its entry id (the service rebuilds
         * some from its own cache) is ours by its media id; and one renamed as well is ours by
         * position, but only while the queue is exactly the DJ's, item for item.
         */
        private fun idOf(item: MediaItem, index: Int): String? {
            item.queueEntryId?.let { return entries[it] }
            if (item.mediaId in place) return item.mediaId
            if (controller.mediaItemCount != order.size) return null
            return order.getOrNull(index)
        }

        /** Where [id] is in the player's queue now, or null when it is not there. */
        private fun indexOf(id: String): Int? =
            (0 until controller.mediaItemCount).firstOrNull { idOf(controller.getMediaItemAt(it), it) == id }

        /**
         * Whether the queue still holds anything of the DJ's. A song the listener added sits among
         * the DJ's items; a queue the listener replaced holds none of them. Only entry ids and clips
         * count here: a new queue can share a song with the DJ's, never an entry or a clip.
         */
        private fun holdsQueue(): Boolean = (0 until controller.mediaItemCount).any { i ->
            val item = controller.getMediaItemAt(i)
            item.queueEntryId?.let(entries::containsKey) == true || synchronized(clips) { item.mediaId in clips }
        }

        /**
         * The set with only the songs that can be queued. The words are written from this, so the DJ
         * never announces a song or an artist that is then missing from the queue.
         */
        private fun playable(set: DjBlock): DjBlock = set.copy(tracks = set.tracks.filter { songs.containsKey(it.id) })

        /** Asks YouTube Music's radio for songs like the listener's favourites, and offers them to the planner. */
        /** True when something was found and added to the pool. */
        private suspend fun addDiscoveries(known: List<DjTrack>, ask: DjRequest?): Boolean {
            val have = known.mapTo(HashSet()) { it.id }
            val found = withContext(Dispatchers.IO) {
                // Seeds drawn at random from the songs the listener plays most, not always the same few: the
                // discoveries are as varied as the taste they come from. An ask can name the artist to follow, or
                // ask for the songs hardly played, further from the usual.
                val streamed = known.filter { it.plays > 0 && it.localUri == null }
                val named = ask?.discoverSeed?.let { artist -> streamed.filter { it.leadArtist.equals(artist, true) } }.orEmpty()
                val seeds = when {
                    named.isNotEmpty() -> named.sortedByDescending { it.plays }.take(DISCOVERY_SEEDS)
                    ask?.discoverFar == true -> streamed.filter { it.plays in 1..FAR_MAX_PLAYS }.shuffled().take(DISCOVERY_SEEDS)
                    else -> null
                } ?: streamed
                    .sortedByDescending { it.plays }
                    .take(DISCOVERY_POOL)
                    .shuffled()
                    .take(DISCOVERY_SEEDS)
                val out = ArrayList<Pair<DjTrack, Song>>()
                for (seed in seeds) {
                    val radio = com.music.bitchord.data.YtMusicRepository.radio(seed.id).getOrNull() ?: continue
                    for (song in radio) {
                        if (song.videoId == seed.id || song.videoId.isBlank() || !have.add(song.videoId)) continue
                        out += DjTrack(
                            id = song.videoId, title = song.title, artist = song.artist, album = song.albumName,
                            art = song.thumbnailUrl, discovery = true, because = seed.leadArtist,
                        ) to song
                    }
                }
                out
            }
            if (closed || found.isEmpty()) return false
            // Back on the main thread: the songs are known before the planner can choose them.
            found.forEach { (track, song) ->
                tracks[track.id] = track
                songs[track.id] = song
            }
            val fresh = found.map { it.first }
            withContext(planning) { planner.addDiscoveries(fresh) }
            return true
        }

        /**
         * Renders a line, or gives the reason it could not be (and null: the words are shown, and the
         * DJ plays on). With [budgetMs], the listener is waiting on it: past the budget it is given up
         * and the set goes in without it, rather than keeping them on the loading screen for the
         * voice's own timeout. A render given up, or no longer wanted, leaves no file behind.
         */
        private suspend fun renderLine(line: String, seed: Long, budgetMs: Long? = null): DjClip? {
            if (!speakable() || line.isBlank()) return null
            // Its own job, so a budget can stop waiting for it: the call itself does not stop when asked.
            val job = scope.async(Dispatchers.IO) {
                runCatching { renderer.render(lang, line, seed) }
                    .also { result -> if (!isActive) result.getOrNull()?.file?.delete() }
            }
            val result = try {
                if (budgetMs == null) job.await() else withTimeoutOrNull(budgetMs) { job.await() }
            } finally {
                if (!job.isCompleted) job.cancel()
            }
            if (result == null) {
                Log.w(TAG, "line not spoken: the voice took longer than ${budgetMs}ms")
                publish { copy(speechError = "timeout") }
                return null
            }
            return result
                .onFailure {
                    Log.w(TAG, "line not spoken", it)
                    publish { copy(speechError = it.message ?: it.javaClass.simpleName) }
                }
                .onSuccess { if (it != null) publish { copy(speechError = null) } }
                .getOrNull()
        }

        /**
         * An item started. One of the DJ's: it names the set's theme, ends the wait for the set that
         * was asked for, and, in the last set queued, plans the one after (from whichever of its items
         * is reached first, so skipping past a set's first song does not leave the queue to run out).
         * One of the listener's: let be, as long as the DJ's items are still in the queue around it.
         */
        private fun entered(item: MediaItem?) {
            if (closed || item == null) return
            val index = controller.currentMediaItemIndex
            val id = idOf(item, index)
            val at = id?.let(place::get)
            if (id == null || at == null) {
                // The queue was taken somewhere else: the session steps aside.
                if (!holdsQueue() && live === this) stop()
                return
            }
            val (set, pos) = at
            // A song, not the DJ's own voice, goes in the memory that keeps the next session fresh.
            if (pos >= 0) DjHistory.record(app, id)
            // The first song of a set is the one it is announced with: remembered, with the set's theme, so the
            // next session neither opens on it nor offers the same set (a request's theme is the listener's own).
            if (pos == 0) {
                DjHistory.recordOpener(app, id)
                sets[set].theme.takeUnless { it is DjTheme.ByRequest }?.let { DjHistory.recordTheme(app, it.key) }
            }
            publish { copy(theme = label(sets[set].theme)) }
            if (awaiting in 0..set) {
                awaiting = -1
                // An ask made while starting is about to be steered to: the wait goes on for it.
                if (queuedRequest == null && live === this) settlePending()
            }
            if (set == sets.lastIndex && set !in planned) planSet(set)
            if (pos == 1 && chatty() && noted.add(set)) commentOnNext(set)
        }

        /**
         * The next set: cut from what a search found for the listener's ask while there is any, else the
         * planner's. When the planner can only give an ask it could not meet (it widened the request to
         * make a set), the ask is searched for instead, once, and answered with what was found.
         */
        private suspend fun chooseBlock(skipToIt: Boolean): DjBlock? {
            takeFound()?.let { return it }
            val block = withContext(planning) { planner.next() } ?: return null
            if (skipToIt && !searched && planner.relaxed && requestText.isNotBlank()) {
                searched = true
                withContext(planning) { planner.release(block.tracks.map { it.id }) }
                searchFor(requestText.trim())
                takeFound()?.let { return it }
                return withContext(planning) { planner.next() }
            }
            return block
        }

        /**
         * Looks for [text] on YouTube Music, in the way of the listener's taste, and keeps what it finds for
         * [takeFound]: the ask in each of their genres, and from each of their favourite artists (whose
         * songs that fit are welcome whatever their genre), unless the ask names a genre of its own, which
         * is searched as it is. See [DjTaste].
         */
        private suspend fun searchFor(text: String) {
            val explicit = DjRequestParser.namesGenre(text)
            val profile = DjTaste.profile(tracks.values, ArtistFacts::genresForDj)
            val plan = DjTaste.queries(text, profile, explicit)
            val found = withContext(Dispatchers.IO) {
                coroutineScope {
                    plan.map { q ->
                        async {
                            q to com.music.bitchord.data.YtMusicRepository.search(q.text, com.music.bitchord.data.model.SearchFilter.SONGS)
                                .getOrNull().orEmpty().mapNotNull {
                                    (it as? com.music.bitchord.data.model.SearchResult.Track)?.song
                                        ?: (it as? com.music.bitchord.data.model.SearchResult.TopTrack)?.song
                                }
                        }
                    }.awaitAll()
                }
            }
            if (closed) return
            val byId = HashMap<String, Song>()
            val hits = found.map { (q, songsFound) ->
                q to songsFound
                    .filter { it.videoId.isNotBlank() && !it.isDjClip && it.videoId !in place }
                    .onEach { byId.putIfAbsent(it.videoId, it) }
                    .map { DjTaste.Hit(it.videoId, it.artist) }
            }
            val known = tracks.values.filter { !it.discovery }.mapTo(HashSet()) { DjRequestParser.fold(it.leadArtist) }
            DjTaste.merge(hits, known, SEARCH_SONGS).forEach { id ->
                val song = byId[id] ?: return@forEach
                val track = tracks.getOrPut(id) {
                    DjTrack(id = id, title = song.title, artist = song.artist, album = song.albumName, art = song.thumbnailUrl, discovery = true)
                }
                songs.putIfAbsent(id, song)
                this.found += track
            }
            foundFor = text.takeIf { this.found.isNotEmpty() }
            // What the DJ says it went by: their genres, when the ask was bent towards them.
            foundTaste = profile.genres.take(2).takeIf { !explicit && it.isNotEmpty() }
                ?.joinToString(if (lang == "es") " y " else " and ")
            lap("search plan=${plan.size} taste=$foundTaste")
        }

        /** A set of what was found: [blockSize] songs, or all that is left when what would remain is too few to make another. */
        private fun takeFound(): DjBlock? {
            val text = foundFor ?: return null
            if (found.size < DjPlanner.MIN_BLOCK) {
                found.clear()
                foundFor = null
                return null
            }
            val n = if (found.size - blockSize in 1 until DjPlanner.MIN_BLOCK) found.size else minOf(blockSize, found.size)
            val taken = List(n) { found.removeFirst() }
            if (found.isEmpty()) foundFor = null
            return DjBlock(DjTheme.ByRequest(text, foundTaste), taken)
        }

        /**
         * Where [mediaId] is in the player's queue after position [after], once the service has it;
         * -1 if it has not shown up in a few seconds. The queue the controller reports follows the
         * service's, a message behind what was just sent.
         */
        private suspend fun awaitIndex(mediaId: String, after: Int): Int {
            val until = SystemClock.elapsedRealtime() + QUEUE_SYNC_MS
            while (true) {
                for (i in (after + 1) until controller.mediaItemCount) {
                    if (controller.getMediaItemAt(i).mediaId == mediaId) return i
                }
                if (SystemClock.elapsedRealtime() >= until) return -1
                delay(40)
            }
        }

        /**
         * Chooses, renders and queues the set after [after], at the end of the queue. With [skipToIt]
         * (a change the listener asked for) the player cuts to it once it is queued, and the voice is
         * given a budget, because the listener is waiting. A set chosen but never queued gives its
         * songs back to the planner and its clip's file is deleted.
         */
        private fun planSet(after: Int, skipToIt: Boolean = false) {
            planned += after
            queueing += scope.launch(Dispatchers.Main) {
                var chosen: DjBlock? = null
                var clip: DjClip? = null
                var queued = false
                try {
                    // Kept from inside the block: called off while the planner works, withContext still
                    // waits for it to finish, and what it took is given back below.
                    chosen = chooseBlock(skipToIt)
                    if (skipToIt) lap("plan chosen theme=${chosen?.theme?.key}")
                    val next = chosen?.let(::playable)?.takeIf { it.tracks.isNotEmpty() }
                    if (next == null) {
                        // Nothing left to plan: the wait for it ends here.
                        if (skipToIt && live === this@Run) settlePending()
                        return@launch
                    }
                    // A DJ who talks over every single song is tiring: after two sets that spoke, one in three
                    // lets the music run. What the listener asked for is always answered.
                    val quiet = !skipToIt && spoken >= 2 && Random.nextInt(3) == 0
                    // A set the listener cut to does not follow the previous one's last song: no "that was…".
                    clip = if (quiet) null else renderLine(
                        writer.blockIntro(next, sets.getOrNull(after).takeUnless { skipToIt }), seed = now + after,
                        budgetMs = if (skipToIt) WAITED_RENDER_MS else null,
                    )
                    spoken = if (clip != null) spoken + 1 else 0
                    if (skipToIt) lap("plan voiced clip=${clip != null}")
                    val pieces = draft(next, clip)
                    // Built off the main thread, like any queue: each one asks the disk about its file.
                    val items = withContext(Dispatchers.Default) { pieces.map { it.song.toMediaItem() } }
                    if (closed) return@launch
                    val firstNew = controller.mediaItemCount
                    sets += next
                    pieces.forEach { register(it, sets.lastIndex) }
                    controller.addMediaItems(items)
                    queued = true
                    if (skipToIt) {
                        // A change the listener asked for does not wait for the song playing: it cuts to
                        // the new set (after any song they queued themselves, which is next by their choice).
                        awaiting = sets.lastIndex
                        // The player call above is only a message to the service: the new items are not in
                        // the queue until it has answered, so "the next item" asked for now is not them.
                        // Waited for by name, then jumped to by position.
                        val at = pieces.firstOrNull()?.let { awaitIndex(it.id, after = controller.currentMediaItemIndex) } ?: -1
                        if (closed) return@launch
                        if (at >= 0) controller.seekTo(at, 0L) else if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
                        lap("plan cut at=$at")
                    } else if (controller.playbackState == Player.STATE_ENDED) {
                        // The listener skipped to the end before this was ready: the player stopped there,
                        // and picks up again from the set that has just arrived.
                        controller.seekTo(firstNew, 0L)
                    }
                } finally {
                    if (!queued) {
                        chosen?.let { c ->
                            val request = c.theme as? DjTheme.ByRequest
                            if (request != null) {
                                // Found by a search, not the planner's: given back to be chosen again.
                                c.tracks.reversed().forEach { found.addFirst(it) }
                                foundFor = request.text
                                foundTaste = request.taste
                            } else {
                                scope.launch(planning) { planner.release(c.tracks.map { it.id }) }
                            }
                        }
                        clip?.file?.delete()
                    }
                }
            }
        }

        /**
         * Renders a remark about a song later in the set that has something worth saying (how often it
         * was played, its playlist, its album and year) and queues it right before that song. A song with
         * nothing to say gets no remark: the title and artist alone are not one.
         */
        private fun commentOnNext(set: Int) {
            queueing += scope.launch(Dispatchers.Main) {
                val block = sets.getOrNull(set) ?: return@launch
                val (track, line) = block.tracks.drop(2)
                    .firstNotNullOfOrNull { t -> writer.trackNote(t)?.let { t to it } } ?: return@launch
                var clip: DjClip? = null
                var queued = false
                try {
                    clip = renderLine(line, seed = now + 7L * set) ?: return@launch
                    val piece = clipPiece(clip)
                    val item = withContext(Dispatchers.Default) { piece.song.toMediaItem() }
                    if (closed) return@launch
                    // Looked up now, not when it was asked for: the queue may have moved since.
                    val current = controller.currentMediaItemIndex
                    val at = indexOf(track.id) ?: return@launch
                    if (at <= current) return@launch
                    register(piece, set, at = order.indexOf(track.id).coerceAtLeast(0))
                    controller.addMediaItem(at, item)
                    queued = true
                } finally {
                    if (!queued) clip?.file?.delete()
                }
            }
        }

        /**
         * The listener changed what they want: what is queued after the song playing is replaced by a
         * set that follows the new request, and the player cuts to it once it is ready. The song
         * playing goes on meanwhile; a song the listener queued themselves stays.
         */
        fun steer(text: String) {
            lapStart = SystemClock.elapsedRealtime()
            requestText = text
            queueing.forEach { it.cancel() }
            queueing.clear()
            awaiting = Int.MAX_VALUE
            queueing += scope.launch(Dispatchers.Main) {
                val known = tracks.values.toList()
                val parsed = if (text.isBlank()) null else withContext(Dispatchers.Default) { DjRequestParser.parse(text, known) }
                withContext(planning) { planner.setRequest(parsed) }
                if (closed) return@launch
                lap("steer parsed understood=${parsed?.understood}")
                found.clear()
                foundFor = null
                foundTaste = null
                searched = false
                // What the library has no words for ("rock en español", a song, a mood of the listener's
                // own) is looked for, not guessed at: the DJ answers with what was asked.
                if (text.isNotBlank() && parsed?.understood != true) {
                    searched = true
                    searchFor(text.trim())
                    if (closed) return@launch
                    lap("steer searched found=${found.size}")
                }
                writer.request = parsed
                val playing = cutAfterCurrent()
                // The song playing finishes its phrase while the new set is chosen and spoken; then it cuts in.
                planSet(playing, skipToIt = true)
            }
        }

        /**
         * Takes every item of the DJ's after the one playing out of the queue, frees their songs for
         * later, and forgets the sets they made up. The listener's own items stay where they are.
         * Returns the set playing (the one of the nearest item of the DJ's at or before the current one).
         */
        private fun cutAfterCurrent(): Int {
            val current = controller.currentMediaItemIndex
            val count = controller.mediaItemCount
            // Read before anything is removed: a lookup by position only holds for the queue as it is.
            val ids = (0 until count).map { idOf(controller.getMediaItemAt(it), it) }
            val playingId = (current downTo 0).firstNotNullOfOrNull { i -> ids.getOrNull(i)?.takeIf { it in place } }
            val playingSet = playingId?.let { place[it]?.first } ?: 0
            // From the end, a run of the DJ's items at a time, so each removal leaves the rest where they were.
            var runEnd = -1
            for (i in count - 1 downTo current + 1) {
                val ours = ids[i] != null
                if (ours && runEnd < 0) runEnd = i + 1
                if (!ours && runEnd >= 0) {
                    controller.removeMediaItems(i + 1, runEnd)
                    runEnd = -1
                }
            }
            if (runEnd >= 0) controller.removeMediaItems(current + 1, runEnd)

            val from = playingId?.let { order.indexOf(it) + 1 } ?: 0
            val cut = LinkedHashSet<String>()
            cut += order.subList(from, order.size)
            for (i in current + 1 until count) ids[i]?.let(cut::add)
            order.removeAll(cut)
            cut.forEach(::unregister)
            val freed = cut.filter { tracks.containsKey(it) }
            if (freed.isNotEmpty()) scope.launch(planning) { planner.release(freed) }
            while (sets.size > playingSet + 1) sets.removeAt(sets.lastIndex)
            planned.removeAll { it >= playingSet }
            noted.removeAll { it > playingSet }
            return playingSet
        }

        fun more() {
            val track = current() ?: return
            scope.launch(planning) { planner.more(track) }
        }

        fun less() {
            val track = current() ?: return
            scope.launch(planning) { planner.less(track) }
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        }

        /** The DJ's song playing now; null for its voice, for a song the listener added, or before it plays. */
        private fun current(): DjTrack? {
            if (!started || closed) return null
            val item = controller.currentMediaItem ?: return null
            return idOf(item, controller.currentMediaItemIndex)?.let(tracks::get)
        }

        fun isSong(mediaId: String?): Boolean {
            if (!started || closed || mediaId == null) return false
            val item = controller.currentMediaItem
            val id = if (item?.mediaId == mediaId) idOf(item, controller.currentMediaItemIndex) else mediaId.takeIf { it in place }
            return id != null && (place[id]?.second ?: -1) >= 0
        }

        /** Marks the session over, at once: nothing it has in flight changes the queue or the state after this. */
        fun markClosed() {
            closed = true
            // A start under way stops at its next step; the hand-back waits for it to have stopped.
            startJob?.cancel()
            queueing.forEach { it.cancel() }
            queueing.clear()
            background.forEach { it.cancel() }
            background.clear()
        }

        /**
         * Hands the player back: waits out a start still in flight (which stops at its next step and
         * changes nothing after it), then turns back on what the session turned off, each waited for,
         * and lets go of its controller. Runs to the end even when whoever waits on it is cancelled.
         *
         * With [tidyQueue] (a Stop: the queue stays and plays on) the DJ's clips come out of it, all
         * but the one playing, so nothing left in it points at a file that is about to be deleted.
         * Without (the queue is about to be replaced, or already was), the queue is not touched. Either
         * way the clip files are deleted after [CLIP_GRACE_MS], by when the queue that held them is gone;
         * the one playing is left, and swept when the next session starts.
         */
        suspend fun close(tidyQueue: Boolean) = withContext(Dispatchers.Main + NonCancellable) {
            markClosed()
            startJob?.let { it.cancel(); it.join() }
            val files = synchronized(clips) { order.mapNotNull { clips.remove(it) } }
            if (!this@Run::controller.isInitialized) {
                deleteLater(files, keep = null)
                return@withContext
            }
            val c = controller
            listener?.let(c::removeListener)
            listener = null
            val playing = runCatching { c.currentMediaItem?.mediaId }.getOrNull()
            if (tidyQueue) runCatching {
                val current = c.currentMediaItemIndex
                val clipIds = files.mapTo(HashSet()) { "$DJ_CLIP_PREFIX${it.id}" }
                for (i in c.mediaItemCount - 1 downTo 0) {
                    if (i != current && c.getMediaItemAt(i).mediaId in clipIds) c.removeMediaItem(i)
                }
            }
            // Back to how the listener had it: only what this session changed, and only if the
            // listener has not set it themselves since.
            if (turnedShuffleOff && !QueueShuffle.enabled.value) runCatching { c.toggleShuffleAndWait() }
            if (turnedAutoplayOff && !AppSettings.autoplay.value) runCatching { c.toggleAutoplayAndWait() }
            if (turnedRepeatOff && c.repeatMode == Player.REPEAT_MODE_OFF) runCatching { c.repeatMode = wasRepeat }
            c.release()
            if (this@Run::renderer.isInitialized) renderer.release()
            deleteLater(files, keep = playing)
        }

        private fun deleteLater(clipsToDelete: List<DjClip>, keep: String?) {
            val doomed = clipsToDelete.filter { "$DJ_CLIP_PREFIX${it.id}" != keep }.map { it.file }
            if (doomed.isEmpty()) return
            scope.launch(Dispatchers.IO) {
                delay(CLIP_GRACE_MS)
                doomed.forEach { it.delete() }
            }
        }

        private fun label(theme: DjTheme): String = when (theme) {
            is DjTheme.ByArtist -> theme.artist
            is DjTheme.ByDecade -> writer.decadeLabel(theme.decade).replaceFirstChar { it.uppercase() }
            is DjTheme.ByGenre -> theme.genre.replaceFirstChar { it.uppercase() }
            is DjTheme.ByAlbum -> theme.album
            is DjTheme.ByMood -> app.getString(
                when (theme.mood) {
                    DjMood.FOCUS -> R.string.dj_theme_focus
                    DjMood.CHILL -> R.string.dj_theme_chill
                    DjMood.ENERGY -> R.string.dj_theme_energy
                    DjMood.ACOUSTIC -> R.string.dj_theme_acoustic
                },
            )
            DjTheme.Favorites -> app.getString(R.string.dj_theme_favorites)
            DjTheme.Rediscover -> app.getString(R.string.home_shelf_rediscover)
            DjTheme.Fresh -> app.getString(R.string.home_shelf_recently_added)
            DjTheme.Discover -> app.getString(R.string.dj_theme_discover)
            is DjTheme.ByPlaylist -> theme.name
            is DjTheme.ByRequest -> theme.text.replaceFirstChar { it.uppercase() }
        }
    }

    /** How many of the listener's favourites seed the discoveries. */
    private const val DISCOVERY_SEEDS = 4

/** How long a set of discoveries is given to find them before the DJ gives the ask up and surprises instead. */
private const val DISCOVER_WAIT_MS = 9_000L

/** The most plays a song has to be a seed for the discoveries further from the listener's usual. */
private const val FAR_MAX_PLAYS = 6

    /** How many of the most played songs the discovery seeds are drawn from. */
    private const val DISCOVERY_POOL = 25

    /** How many of the listener's artists have their genres asked of Last.fm when a session starts. */
    private const val GENRE_LOOKUPS = 40

    /**
     * How long a line the listener is waiting on (the opening, the first line after a change) may
     * take before the set goes in without it. Well inside [PENDING_TIMEOUT_MS], with room for the
     * library load around it; lines rendered a set ahead have the voice's own timeout instead.
     */
    private const val WAITED_RENDER_MS = 12_000L

    /** How long the clips of an ended session are kept, so a queue being replaced never meets a missing file. */
    private const val CLIP_GRACE_MS = 10_000L

    private class NotEnoughMusic : Exception()

    /** The artist with the most plays in the last month, when they clearly lead the others. */
    private fun topArtistThisMonth(tracks: List<DjTrack>): String? {
        val byArtist = tracks.filter { it.plays30 > 0 }.groupBy { it.leadArtist }
            .mapValues { (_, ts) -> ts.sumOf { it.plays30 } }
            .entries.sortedByDescending { it.value }
        val top = byArtist.firstOrNull() ?: return null
        val second = byArtist.getOrNull(1)?.value ?: 0
        return top.key.takeIf { top.value >= 5 && top.value >= 2 * second }
    }
}
