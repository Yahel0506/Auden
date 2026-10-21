package dev.auden.dj

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.toMediaItem
import dev.auden.dj.voice.DjClip
import dev.auden.dj.voice.DjClipRenderer
import dev.auden.dj.voice.VoicePackStore
import dev.auden.dj.voice.VoicePacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.Locale

/** What the DJ's sheet and the player show about the session. */
data class DjUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    /** What the current block is about, in words ("Radiohead", "Tranquilo", "Los 90"). */
    val theme: String? = null,
    /** Whether the DJ can speak in this language, and the pack's state if it can. */
    val voice: Voice = Voice.Unavailable,
    /** Why the last start did not happen, if it did not. */
    val problem: Problem? = null,
) {
    sealed interface Voice {
        /** No voice exists for the app's language: the DJ plays, and the words are on screen. */
        data object Unavailable : Voice
        /** A voice exists and has to be downloaded first. */
        data class NotInstalled(val pack: VoicePacks.Pack) : Voice
        data class Downloading(val pack: VoicePacks.Pack, val fraction: Float) : Voice
        data class Ready(val pack: VoicePacks.Pack) : Voice
    }

    enum class Problem { TOO_FEW_SONGS, NO_PLAYER }
}

/**
 * One DJ session: themed sets of songs, each introduced by something the DJ says.
 *
 * The DJ is queued like music. A line is rendered into a clip and placed in the queue before the
 * set it introduces, so the player moves from the last song of a set to the DJ, and from the DJ to
 * the first song of the next set, as it would between any two tracks. Sets are planned one ahead:
 * when a set starts, the one after it is chosen, its line is rendered, and both are queued, so the
 * queue is never empty and the voice is never waiting for a song to end.
 *
 * The session lives in the process, not in a screen, and drives the player through its own
 * controller: it goes on with the phone locked. If the listener takes the queue somewhere else, the
 * session steps aside rather than fighting for it.
 */
object DjSession {
    private const val TAG = "AudenDj"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DjUiState())
    val state: StateFlow<DjUiState> = _state.asStateFlow()

    private var run: Run? = null

    /** The rendered lines of the running session, by the clip's media id. */
    private val clips = HashMap<String, DjClip>()

    /** The words of a DJ clip on screen while it plays; null for any other song. */
    fun clipOf(mediaId: String?): DjClip? = mediaId?.let { synchronized(clips) { clips[it] } }

    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        VoicePackStore.init(app)
        _state.value = DjUiState(voice = voiceFor(language(app)))
        // The sheet shows the voice's download as it runs, whether or not a session is on.
        scope.launch { VoicePackStore.states.collect { refreshVoice() } }
    }

    private fun refreshVoice() {
        val app = appContext ?: return
        _state.value = _state.value.copy(voice = voiceFor(language(app)))
    }

    /** Starts a session: from a typed [request], or on the DJ's own choice when it is blank. */
    fun start(context: Context, request: String?) {
        if (_state.value.starting) return
        stop()
        val app = context.applicationContext
        _state.value = DjUiState(starting = true, voice = voiceFor(language(app)))
        scope.launch {
            val session = Run(app, request?.trim().orEmpty())
            run = session
            runCatching { session.begin() }
                .onFailure { error ->
                    Log.w(TAG, "could not start", error)
                    run = null
                    _state.value = DjUiState(
                        problem = if (error is NotEnoughMusic) DjUiState.Problem.TOO_FEW_SONGS else DjUiState.Problem.NO_PLAYER,
                        voice = voiceFor(language(app)),
                    )
                }
        }
    }

    /**
     * Changes what the DJ is asked for. Takes effect from the next set: the one already queued is
     * replaced, the one playing is left alone. A blank request is a surprise again.
     */
    fun request(context: Context, text: String) {
        val session = run ?: return start(context, text)
        session.steer(text)
    }

    fun stop() {
        run?.finish()
        run = null
        synchronized(clips) { clips.clear() }
        renderers.forEach { it.clearClips(); it.release() }
        renderers.clear()
        refreshVoice()
        _state.value = _state.value.copy(running = false, starting = false, theme = null, problem = null)
    }

    /** More like the song playing now. */
    fun more() { run?.more() }

    /** Less like the song playing now: its artist leaves the session, and the set moves on. */
    fun less() { run?.less() }

    /** Starts the download of the voice for the app's language, if it has one and it is not here. */
    fun downloadVoice(context: Context) {
        val pack = VoicePacks.forLanguage(language(context.applicationContext)) ?: return
        VoicePackStore.download(pack)
    }

    private fun language(context: Context): String =
        AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?: Locale.getDefault().language

    /** Where the voice stands for [language]: unavailable, to download, or ready. */
    private fun voiceFor(language: String): DjUiState.Voice {
        val pack = VoicePacks.forLanguage(language) ?: return DjUiState.Voice.Unavailable
        val states = VoicePackStore.states.value
        return when (val s = states[pack.id]) {
            is VoicePackStore.State.Downloading -> DjUiState.Voice.Downloading(pack, s.fraction)
            VoicePackStore.State.Ready -> DjUiState.Voice.Ready(pack)
            else -> if (VoicePackStore.isReady(pack)) DjUiState.Voice.Ready(pack) else DjUiState.Voice.NotInstalled(pack)
        }
    }


    /**
     * One running session. Planning and rendering run on [scope]; every player call runs on the main
     * thread. The queue is kept as [order], one media id per item, and [place] says which set each id
     * belongs to. A set is a clip (its line, when there is a voice for it) followed by its songs.
     */
    private class Run(private val app: Context, requestText: String) {
        private val lang = language(app)
        private val blockSize = AppSettings.djBlockSize.value
        private val speakable = AppSettings.djVoice.value &&
            VoicePacks.forLanguage(lang)?.let(VoicePackStore::isReady) == true &&
            DjPhrasesCheck.supports(app, lang)
        private val chatty = AppSettings.djChatty.value && speakable
        private val now = System.currentTimeMillis()
        private val songs = HashMap<String, Song>()
        private val tracks = HashMap<String, DjTrack>()
        private val clipSongs = HashMap<String, Song>()
        private val order = ArrayList<String>()
        private val place = HashMap<String, Pair<Int, Int>>()
        private val setStart = ArrayList<Int>()
        private val sets = ArrayList<DjBlock>()
        private val noted = HashSet<Int>()
        private val queueing = ArrayList<Job>()
        private var listener: Player.Listener? = null
        private var requestText = requestText
        private var closed = false
        private lateinit var controller: MediaController
        private lateinit var planner: DjPlanner
        private lateinit var writer: DjWriter
        private lateinit var renderer: DjClipRenderer

        suspend fun begin() {
            val loaded = DjPool.load(app)
            if (loaded.tracks.size < DjPlanner.MIN_BLOCK) throw NotEnoughMusic()
            tracks.putAll(loaded.tracks.associateBy { it.id })
            songs.putAll(loaded.songs)
            val parsed = requestText.takeIf { it.isNotBlank() }?.let { DjRequestParser.parse(it, loaded.tracks) }
            planner = DjPlanner(loaded.tracks, now, seed = now, blockSize = blockSize, request = parsed, genresOf = ArtistFacts::genresForDj)
            // The Last.fm genres of the artists the listener plays most, asked for once and kept: they
            // give a track its mood. Off the main thread, and the sets already chosen do not wait for it.
            queueing += scope.launch(Dispatchers.IO) {
                loaded.tracks.groupBy { it.leadArtist }
                    .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
                    .take(GENRE_LOOKUPS)
                    .forEach { ArtistFacts.fetchForDj(it.key) }
            }
            // New to the listener but on the taste they show: the radio of what they know, fetched
            // once here and kept for the session. Surprises only; a request names its songs itself.
            if (parsed == null) queueing += scope.launch { addDiscoveries(loaded.tracks) }
            val time = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            writer = DjWriter(
                AndroidPhrases(app, lang), lang, seed = now,
                hour = time.hour,
                weekend = time.dayOfWeek.value >= 6,
                topArtist = topArtistThisMonth(loaded.tracks),
            )
            renderer = DjClipRenderer(app).also { renderers += it }

            val first = planner.next()?.let(::playable) ?: throw NotEnoughMusic()
            val opening = writer.opening(LocalTime.now().hour, first, parsed, planner.relaxed)
            val openingClip = renderLine(opening, seed = now)
            appendSet(first, openingClip)
            val second = planner.next()?.let(::playable)
            if (second != null) {
                val clip = renderLine(writer.blockIntro(second, first), seed = now + 1)
                appendSet(second, clip)
            }

            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controller = withContext(Dispatchers.Main) { MediaController.Builder(app, token).buildAsync().await() }
            withContext(Dispatchers.Main) {
                controller.playSongs(order.mapNotNull(::songFor), 0, shuffle = false)
                listener = object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = entered(mediaItem?.mediaId)
                }.also(controller::addListener)
            }
            _state.value = DjUiState(running = true, theme = label(first.theme), voice = voiceFor(lang))
            entered(order.firstOrNull())
        }

        /** Appends a set to the end of the queue: its clip (if any), then its songs. */
        private fun appendSet(set: DjBlock, clip: DjClip?) {
            val index = sets.size
            sets += set
            setStart += order.size
            clip?.let { order += djSong(it); place[order.last()] = index to -1 }
            set.tracks.forEachIndexed { p, t ->
                if (songs.containsKey(t.id)) {
                    order += t.id
                    place[t.id] = index to p
                }
            }
        }

        private fun songFor(id: String): Song? = songs[id] ?: clipSongs[id]

        /**
         * The set with only the songs that can be queued. The words are written from this, so the DJ
         * never announces a song or an artist that is then missing from the queue.
         */
        private fun playable(set: DjBlock): DjBlock = set.copy(tracks = set.tracks.filter { songs.containsKey(it.id) })

        /** Asks YouTube Music's radio for songs like the listener's favourites, and offers them to the planner. */
        private suspend fun addDiscoveries(known: List<DjTrack>) {
            val seeds = known.filter { it.plays > 0 && it.localUri == null }
                .sortedByDescending { it.plays }
                .take(DISCOVERY_SEEDS)
            val found = ArrayList<DjTrack>()
            for (seed in seeds) {
                val radio = com.music.bitchord.data.YtMusicRepository.radio(seed.id).getOrNull() ?: continue
                for (song in radio) {
                    if (song.videoId == seed.id || song.videoId.isBlank() || song.videoId in tracks) continue
                    val track = DjTrack(
                        id = song.videoId, title = song.title, artist = song.artist, album = song.albumName,
                        art = song.thumbnailUrl, discovery = true,
                    )
                    tracks[track.id] = track
                    songs[track.id] = song
                    found += track
                }
            }
            if (found.isNotEmpty() && !closed) planner.addDiscoveries(found)
        }

        private fun djSong(clip: DjClip): String {
            val id = "dj:${clip.id}"
            clipSongs[id] = Song(
                videoId = id,
                title = app.getString(R.string.dj_title),
                artist = app.getString(R.string.dj_title),
                thumbnailUrl = null,
                durationText = "%d:%02d".format(clip.durationMs / 60_000, clip.durationMs / 1000 % 60),
                localUri = Uri.fromFile(clip.file).toString(),
                playbackSourceType = PlaybackSourceType.QUEUE,
                playbackSource = app.getString(R.string.dj_title),
            )
            synchronized(clips) { clips[id] = clip }
            return id
        }

        private suspend fun renderLine(line: String, seed: Long): DjClip? {
            if (!speakable || line.isBlank()) return null
            return runCatching { renderer.render(lang, line, seed) }
                .onFailure { Log.w(TAG, "line not spoken", it) }
                .getOrNull()
        }

        /** A song started: a set's first song plans the one after; the planned sets stay one ahead. */
        private fun entered(mediaId: String?) {
            if (closed || mediaId == null) return
            val at = place[mediaId] ?: run {
                // A version switch can rename the entry; by position it is still ours.
                val i = runCatching { controller.currentMediaItemIndex }.getOrNull() ?: -1
                order.getOrNull(i)?.let(place::get)
            }
            if (at == null) {
                // Something else was queued by the listener: the session steps aside.
                scope.launch(Dispatchers.Main) { if (run === this@Run) stop() }
                return
            }
            val (set, pos) = at
            _state.value = _state.value.copy(theme = label(sets[set].theme))
            if (pos == 0 && sets.size <= set + 1) planSet(set)
            if (pos == 1 && chatty && noted.add(set)) commentOnNext(set)
        }

        /** Chooses, renders and queues the set after [set]. */
        private fun planSet(set: Int, skipToIt: Boolean = false) {
            queueing += scope.launch {
                val next = planner.next()?.let(::playable) ?: return@launch
                val clip = renderLine(writer.blockIntro(next, sets[set]), seed = now + set)
                if (closed) return@launch
                withContext(Dispatchers.Main) {
                    if (closed) return@withContext
                    if (controller.mediaItemCount != order.size) return@withContext
                    val before = order.size
                    appendSet(next, clip)
                    controller.addMediaItems(
                        order.subList(before, order.size).mapNotNull(::songFor).map { it.toMediaItem() },
                    )
                    // A change the listener asked for does not wait for the song playing: it cuts to the new set.
                    if (skipToIt && controller.hasNextMediaItem()) controller.seekToNextMediaItem()
                }
            }
        }

        /** Renders a short remark about the song two places ahead and queues it right after the current one. */
        private fun commentOnNext(set: Int) {
            queueing += scope.launch {
                val upcoming = sets[set].tracks.getOrNull(2) ?: return@launch
                val line = writer.trackNote(upcoming) ?: return@launch
                val clip = renderLine(line, seed = now + 7L * set) ?: return@launch
                if (closed) return@launch
                withContext(Dispatchers.Main) {
                    if (closed) return@withContext
                    val index = runCatching { controller.currentMediaItemIndex }.getOrNull() ?: return@withContext
                    if (controller.mediaItemCount != order.size) return@withContext
                    val id = djSong(clip)
                    place[id] = set to -1
                    controller.addMediaItem(index + 1, songFor(id)!!.toMediaItem())
                    order.add(index + 1, id)
                    for (i in setStart.indices) if (setStart[i] > index) setStart[i] = setStart[i] + 1
                }
            }
        }

        /**
         * The listener changed what they want: the set not yet reached is replaced by one that
         * follows the new request. The set playing now is left alone.
         */
        fun steer(text: String) {
            requestText = text
            queueing.forEach { it.cancel() }
            queueing.clear()
            scope.launch {
                val parsed = text.takeIf { it.isNotBlank() }?.let { DjRequestParser.parse(it, tracks.values.toList()) }
                planner.setRequest(parsed)
                val current = runCatching { withContext(Dispatchers.Main) { controller.currentMediaItemIndex } }.getOrNull() ?: 0
                val playing = order.getOrNull(current)?.let(place::get)?.first ?: 0
                cutAfter(current, playing)
                // The song playing finishes its phrase while the new set is chosen and spoken; then it cuts in.
                planSet(playing, skipToIt = true)
            }
        }

        /** Takes every queued item after [index] out of the queue and frees its songs for later. */
        private suspend fun cutAfter(index: Int, playingSet: Int) {
            withContext(Dispatchers.Main) {
                if (controller.mediaItemCount == order.size && index + 1 < order.size) {
                    controller.removeMediaItems(index + 1, order.size)
                }
            }
            val removed = order.subList(index + 1, order.size).toList()
            removed.forEach { id ->
                place.remove(id)
                clipSongs.remove(id)
                synchronized(clips) { clips.remove(id) }
            }
            planner.release(removed.filter { tracks.containsKey(it) })
            order.subList(index + 1, order.size).clear()
            while (sets.size > playingSet + 1) {
                sets.removeAt(sets.lastIndex)
                setStart.removeAt(setStart.lastIndex)
            }
        }

        fun more() { current()?.let(planner::more) }

        fun less() {
            current()?.let(planner::less)
            scope.launch(Dispatchers.Main) { if (!closed && controller.hasNextMediaItem()) controller.seekToNextMediaItem() }
        }

        private fun current(): DjTrack? = tracks[runCatching { controller.currentMediaItem?.mediaId }.getOrNull()]

        fun finish() {
            closed = true
            queueing.forEach { it.cancel() }
            val l = listener
            if (!this::controller.isInitialized) return
            val c = controller
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
            DjTheme.Discover -> app.getString(R.string.dj_theme_discover)
        }
    }

    private val renderers = ArrayList<DjClipRenderer>()

    /** How many of the listener's favourites seed the discoveries. */
    private const val DISCOVERY_SEEDS = 3

    /** How many of the listener's artists have their genres asked of Last.fm when a session starts. */
    private const val GENRE_LOOKUPS = 40

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
