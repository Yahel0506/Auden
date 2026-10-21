package dev.auden.dj

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.Locale

/** What the DJ sheet shows. */
data class DjUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    /** What the current block is about, in words ("Radiohead", "los 90", "Chill"). */
    val theme: String? = null,
    /** The speech engine could not be reached or has no voice for the language; the DJ plays on, silently. */
    val voiceUnavailable: Boolean = false,
    /** Why the last start did not happen, if it did not. */
    val problem: Problem? = null,
) {
    enum class Problem { TOO_FEW_SONGS, NO_PLAYER }
}

/**
 * One DJ session: themed blocks of songs, a line of speech between them.
 *
 * The session lives in the process, not in the screen that started it, and drives the player
 * through its own controller: with the phone locked and the activity gone the DJ goes on. It keeps
 * two blocks queued ahead of what is playing and plans the next as one is entered, so the queue is
 * never longer than it needs to be and never runs dry. If the listener rebuilds the queue — plays
 * something else — the session notices a song it did not queue and steps aside.
 */
object DjSession {
    private const val TAG = "AudenDj"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DjUiState())
    val state: StateFlow<DjUiState> = _state.asStateFlow()

    private var run: Run? = null
    private var voice: DjVoice? = null

    /** Starts a session, from a typed [request] or on the DJ's own choice when it is null or blank. */
    fun start(context: Context, request: String?) {
        if (_state.value.starting) return
        stop()
        val app = context.applicationContext
        _state.value = DjUiState(starting = true)
        scope.launch {
            val session = runCatching { Run(app, request?.trim().orEmpty()).also { it.begin() } }
                .onFailure { Log.w(TAG, "could not start", it) }
                .getOrElse {
                    _state.value = DjUiState(
                        problem = if (it is NotEnough) DjUiState.Problem.TOO_FEW_SONGS else DjUiState.Problem.NO_PLAYER,
                    )
                    return@launch
                }
            run = session
        }
    }

    fun stop() {
        run?.finish()
        run = null
        voice?.stop()
        _state.value = DjUiState()
    }

    /** More like the song playing now. */
    fun more() { run?.more() }

    /** Less like the song playing now: its artist is out for the session, and playback moves on. */
    fun less() { run?.less() }

    private class NotEnough : Exception()

    private class Run(private val app: Context, private val requestText: String) {
        private val settings = Settings()
        private lateinit var controller: MediaController
        private lateinit var planner: DjPlanner
        private lateinit var writer: DjWriter
        private val blocks = ArrayList<DjBlock>()
        private val intros = ArrayList<String>()
        private val songs = HashMap<String, Song>()
        private val tracks = HashMap<String, DjTrack>()
        /** Where each queued song sits: its block and its place in it. */
        private val place = HashMap<String, Pair<Int, Int>>()
        /** The queue as the DJ built it, in order. */
        private val order = ArrayList<String>()
        private val spoken = HashSet<Int>()
        private var talk: Job? = null
        private var listener: Player.Listener? = null
        private var closed = false
        private val language = Locale.getDefault().language.takeIf { it == "es" } ?: "en"

        private data class Settings(
            val voice: Boolean = AppSettings.djVoice.value,
            val chatty: Boolean = AppSettings.djChatty.value,
            val blockSize: Int = AppSettings.djBlockSize.value,
        )

        suspend fun begin() {
            val loaded = DjPool.load(app)
            val now = System.currentTimeMillis()
            val parsed = requestText.takeIf { it.isNotBlank() }?.let { DjRequestParser.parse(it, loaded.tracks) }
            planner = DjPlanner(loaded.tracks, now, seed = now, blockSize = settings.blockSize, request = parsed)
            writer = DjWriter(AndroidPhrases(app, language), language, seed = now)
            songs.putAll(loaded.songs)
            loaded.tracks.forEach { tracks[it.id] = it }

            val first = planner.next() ?: throw NotEnough()
            blocks += first
            intros += writer.opening(LocalTime.now().hour, first, parsed, planner.relaxed)
            planner.next()?.let { next ->
                blocks += next
                intros += writer.blockIntro(next, first)
            }
            blocks.forEachIndexed { b, block -> block.tracks.forEachIndexed { p, t -> place[t.id] = b to p; order += t.id } }

            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controller = withContext(Dispatchers.Main) { MediaController.Builder(app, token).buildAsync().await() }
            withContext(Dispatchers.Main) {
                controller.playSongs(blocks.flatMap { it.tracks }.mapNotNull { songs[it.id] }, 0, shuffle = false)
                listener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = entered(mediaItem?.mediaId)
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) end() }
                }.also(controller::addListener)
            }
            _state.value = DjUiState(running = true, theme = label(first.theme))
            if (settings.voice) {
                val voice = (voice ?: DjVoice(app).also { voice = it })
                val ok = voice.prepare(Locale.getDefault().let { if (it.language == language) it else Locale(language) })
                if (!ok) _state.value = _state.value.copy(voiceUnavailable = true)
            }
            withContext(Dispatchers.Main) { entered(controller.currentMediaItem?.mediaId) }
        }

        /** A song started. Plans ahead when a block is entered, and says the line that belongs to it. */
        private fun entered(mediaId: String?) {
            if (closed || mediaId == null) return
            // By id, and when the service has swapped the id (a version switch keeps the queue the
            // same length but renames the entry) by position. Anything else is the listener's own queue.
            val at = place[mediaId] ?: order.getOrNull(controller.currentMediaItemIndex)
                ?.takeIf { controller.mediaItemCount == order.size }?.let(place::get)
            if (at == null) { end(); return }
            val (b, p) = at
            _state.value = _state.value.copy(theme = label(blocks[b].theme))
            talk?.cancel()
            if (p == 0 && spoken.add(b)) {
                say(mediaId, intros.getOrNull(b))
                if (b >= blocks.size - 1) scope.launch { planAhead() }
            } else if (p == 2 && settings.chatty && b !in noted) {
                noted += b
                tracks[mediaId]?.let { say(mediaId, writer.trackNote(it)) }
            }
        }
        private val noted = HashSet<Int>()

        /** Talks over the opening of the song: a moment in, so the song is heard before the voice. */
        private fun say(mediaId: String, line: String?) {
            if (!settings.voice || line.isNullOrBlank() || _state.value.voiceUnavailable) return
            val voice = voice ?: return
            talk = scope.launch {
                delay(TALK_AFTER_MS)
                val stillHere = withContext(Dispatchers.Main) { controller.currentMediaItem?.mediaId == mediaId && controller.isPlaying }
                if (stillHere) voice.speak(line)
            }
        }

        private suspend fun planAhead() {
            val last = blocks.lastOrNull() ?: return
            val next = planner.next() ?: return
            val b = blocks.size
            blocks += next
            intros += writer.blockIntro(next, last)
            next.tracks.forEachIndexed { p, t -> place[t.id] = b to p; order += t.id }
            val items = next.tracks.mapNotNull { songs[it.id] }.map { it.toMediaItem() }
            withContext(Dispatchers.Main) { if (!closed) controller.addMediaItems(items) }
        }

        fun more() { current()?.let(planner::more) }

        fun less() {
            current()?.let(planner::less)
            scope.launch(Dispatchers.Main) { if (!closed && controller.hasNextMediaItem()) controller.seekToNextMediaItem() }
        }

        private fun current(): DjTrack? = tracks[runCatching { controller.currentMediaItem?.mediaId }.getOrNull()]

        private fun end() {
            if (closed) return
            scope.launch { if (run === this@Run) stop() }
        }

        fun finish() {
            closed = true
            talk?.cancel()
            val l = listener
            val c = if (this::controller.isInitialized) controller else return
            scope.launch(Dispatchers.Main) {
                l?.let(c::removeListener)
                c.release()
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
        }
    }

    /** How long a song plays before the DJ speaks over it. */
    private const val TALK_AFTER_MS = 1_400L
}
