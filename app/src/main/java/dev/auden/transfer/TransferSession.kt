package dev.auden.transfer

import android.content.Context
import dev.auden.library.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Transfer your music" as a process-wide session: what a source offers, what the listener picked, and the import
 * running — in a scope of its own, so an import goes on when the page is left and is there to see when it is
 * opened again. One at a time. Reads from the service and writes only to Auden (see [ImportEngine]).
 */
object TransferSession {
    /** What a source has to bring over. A category that could not be read is in [unreadable], not just empty. */
    data class Offer(
        val service: Service,
        val playlists: List<RemoteCollection>,
        val albums: List<RemoteCollection>,
        val artists: List<RemoteArtist>,
        val podcasts: List<RemoteArtist>,
        val unreadable: Set<ImportStep>,
    )

    sealed interface State {
        data object Idle : State
        data class Loading(val service: Service) : State
        data class Choosing(val offer: Offer) : State
        /**
         * [fraction] is the whole transfer's, 0 to 1, across everything ticked; [unit] of [units] is which of the
         * things being brought over this is (likes, each playlist or album, the artists), counted from 1.
         */
        data class Running(
            val service: Service,
            val progress: ImportProgress?,
            val fraction: Float = 0f,
            val unit: Int = 1,
            val units: Int = 1,
        ) : State
        data class Done(val service: Service, val report: ImportReport) : State
        data class Failed(val service: Service, val reason: String?) : State
    }

    /** What is ticked on the chooser; kept here so the page and the bar pinned under it read the same thing. */
    enum class Kind { PLAYLISTS, ALBUMS, ARTISTS, PODCASTS }

    data class Selection(
        val likes: Boolean = false,
        val playlists: Set<String> = emptySet(),
        val albums: Set<String> = emptySet(),
        val artists: Set<String> = emptySet(),
        val podcasts: Set<String> = emptySet(),
    ) {
        val count: Int get() = (if (likes) 1 else 0) + playlists.size + albums.size + artists.size + podcasts.size

        fun of(kind: Kind): Set<String> = when (kind) {
            Kind.PLAYLISTS -> playlists
            Kind.ALBUMS -> albums
            Kind.ARTISTS -> artists
            Kind.PODCASTS -> podcasts
        }

        fun with(kind: Kind, ids: Set<String>): Selection = when (kind) {
            Kind.PLAYLISTS -> copy(playlists = ids)
            Kind.ALBUMS -> copy(albums = ids)
            Kind.ARTISTS -> copy(artists = ids)
            Kind.PODCASTS -> copy(podcasts = ids)
        }
    }

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** Whether the Transfer page is on screen, for the bar that is drawn over the app while it is. */
    val pageOpen = MutableStateFlow(false)

    fun toggleLikes() { _selection.value = _selection.value.let { it.copy(likes = !it.likes) } }

    fun toggle(kind: Kind, id: String) {
        _selection.value = _selection.value.let { it.with(kind, it.of(kind).let { ids -> if (id in ids) ids - id else ids + id }) }
    }

    /** Ticks every id of [kind], or clears them when they are all ticked already. */
    fun toggleAll(kind: Kind, ids: Collection<String>) {
        _selection.value = _selection.value.let { it.with(kind, if (it.of(kind).size == ids.size) emptySet() else ids.toSet()) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun source(service: Service): MusicSource =
        if (service == Service.SPOTIFY) SpotifySource() else YtMusicSource()

    /** Looks at what [service] has: its playlists, albums and followed artists. The likes need no listing. */
    fun open(service: Service) {
        if (_state.value is State.Running) return
        job?.cancel()
        _selection.value = Selection()
        _state.value = State.Loading(service)
        job = scope.launch {
            val src = source(service)
            val unreadable = mutableSetOf<ImportStep>()
            suspend fun <T> read(step: ImportStep, load: suspend () -> List<T>): List<T> =
                runCatching { load() }.getOrElse { unreadable += step; emptyList() }
            val playlists = read(ImportStep.PLAYLIST) { src.playlists() }
            val albums = read(ImportStep.ALBUM) { src.albums() }
            val artists = read(ImportStep.ARTISTS) { src.artists() }
            // Podcast shows are followed like artists: a failure to read them is not worth failing the page for.
            val podcasts = runCatching { src.podcasts() }.getOrDefault(emptyList())
            _state.value = if (unreadable.size == 3) {
                State.Failed(service, null)
            } else {
                State.Choosing(Offer(service, playlists, albums, artists, podcasts, unreadable))
            }
        }
    }

    /** Brings over what is ticked, from the source whose offer is on the chooser. */
    fun startSelected(context: Context) {
        val offer = (_state.value as? State.Choosing)?.offer ?: return
        val ticked = _selection.value
        if (ticked.count == 0) return
        start(
            context, offer.service,
            ImportSelection(
                likes = ticked.likes,
                collections = (offer.playlists + offer.albums).filter { it.sourceId in ticked.playlists || it.sourceId in ticked.albums },
                // Podcast shows are followed the way artists are, and shelved apart by their page's id.
                artists = offer.artists.filter { it.id in ticked.artists } + offer.podcasts.filter { it.id in ticked.podcasts },
            ),
        )
    }

    /** Stops a transfer in progress: what it had stored stays (a later one skips it), and the page starts over. */
    fun cancel() {
        if (_state.value !is State.Running) return
        job?.cancel()
        _state.value = State.Idle
    }

    /** Brings [selection] over from [service]. */
    fun start(context: Context, service: Service, selection: ImportSelection) {
        if (_state.value is State.Running) return
        val app = context.applicationContext
        job?.cancel()
        // The units of work: the likes, each playlist and album, and the artists together. The bar is the share of
        // them done, with the one in hand counted by how far along its own matching is.
        val units = ((if (selection.likes) 1 else 0) + selection.collections.size +
            (if (selection.artists.isNotEmpty()) 1 else 0)).coerceAtLeast(1)
        _state.value = State.Running(service, null, 0f, 1, units)
        job = scope.launch {
            var lastKey: String? = null
            var finished = 0
            var best = 0f
            val report = runCatching {
                TransferMigrations.engine(app).import(source(service), selection) { progress ->
                    val key = "${progress.step}:${progress.title}"
                    if (lastKey != null && key != lastKey) finished = (finished + 1).coerceAtMost(units - 1)
                    lastKey = key
                    val within = when {
                        progress.total > 0 -> progress.done.toFloat() / progress.total
                        // A length not known yet (a long list of likes being read): ever closer to done, never there.
                        progress.done > 0 -> progress.done / (progress.done + 60f)
                        else -> 0f
                    }
                    // Never backwards, and never full until it is: the last unit's tail is the report's.
                    best = maxOf(best, ((finished + within) / units).coerceIn(0f, 0.99f))
                    _state.value = State.Running(service, progress, best, finished + 1, units)
                }
            }
            // Likes brought over by hand from YouTube Music are the likes the launch-time move was after: once the whole
            // read went through, that move has nothing left to do (and a give-up there is lifted).
            report.getOrNull()?.let { r ->
                if (service == Service.YT_MUSIC && selection.likes && r.complete) {
                    com.music.bitchord.data.OfflinePlaylists.flush(markAccountLikesImported = true)
                }
            }
            _state.value = report.fold(
                onSuccess = { State.Done(service, it) },
                onFailure = { State.Failed(service, it.message) },
            )
        }
    }

    /** Back to the start, unless an import is running. */
    fun reset() {
        if (_state.value is State.Running) return
        job?.cancel()
        _state.value = State.Idle
    }
}
