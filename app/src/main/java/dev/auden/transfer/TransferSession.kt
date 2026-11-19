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
        val unreadable: Set<ImportStep>,
    )

    sealed interface State {
        data object Idle : State
        data class Loading(val service: Service) : State
        data class Choosing(val offer: Offer) : State
        data class Running(val service: Service, val progress: ImportProgress?) : State
        data class Done(val service: Service, val report: ImportReport) : State
        data class Failed(val service: Service, val reason: String?) : State
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
        _state.value = State.Loading(service)
        job = scope.launch {
            val src = source(service)
            val unreadable = mutableSetOf<ImportStep>()
            suspend fun <T> read(step: ImportStep, load: suspend () -> List<T>): List<T> =
                runCatching { load() }.getOrElse { unreadable += step; emptyList() }
            val playlists = read(ImportStep.PLAYLIST) { src.playlists() }
            val albums = read(ImportStep.ALBUM) { src.albums() }
            val artists = read(ImportStep.ARTISTS) { src.artists() }
            _state.value = if (unreadable.size == 3) {
                State.Failed(service, null)
            } else {
                State.Choosing(Offer(service, playlists, albums, artists, unreadable))
            }
        }
    }

    /** Brings [selection] over from [service]. */
    fun start(context: Context, service: Service, selection: ImportSelection) {
        if (_state.value is State.Running) return
        val app = context.applicationContext
        job?.cancel()
        _state.value = State.Running(service, null)
        job = scope.launch {
            val report = runCatching {
                TransferMigrations.engine(app).import(source(service), selection) { progress ->
                    _state.value = State.Running(service, progress)
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
