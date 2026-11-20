package dev.auden.transfer

import android.content.Context
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.SpotifyException
import dev.auden.library.ImportedOrigin
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistMerge
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Why a playlist could not be refreshed from its source: [RefreshException.reason]. */
sealed interface RefreshError {
    /** No such playlist (deleted meanwhile). */
    data object NotFound : RefreshError

    /** Made here: there is no source to refresh from. */
    data object NoOrigin : RefreshError

    /** A copy of a service's liked songs: those are likes now, to be turned into likes rather than refreshed. */
    data object LikesList : RefreshError

    /** A Spotify album: Spotify gives no album's track listing (an imported album is the YouTube Music one). */
    data object NoListing : RefreshError

    /** No account for [service] is connected (or, for YouTube Music, the list could not be read without one). */
    data class NotConnected(val service: Service) : RefreshError

    /** An account is connected but [service] no longer accepts its session: sign in again. */
    data class Expired(val service: Service) : RefreshError

    /** The source read back no songs at all — deleted, made private, or emptied. Nothing is changed. */
    data class EmptySource(val service: Service) : RefreshError

    /** Anything else: the network, a page that failed. */
    data class Failed(val service: Service, val message: String?) : RefreshError
}

class RefreshException(val reason: RefreshError, cause: Throwable? = null) : Exception(reason.toString(), cause)

/** What [PlaylistRefresher] reads and writes on the device. See [AndroidRefreshStore]. */
interface RefreshStore {
    suspend fun playlist(id: String): LocalPlaylist?

    suspend fun playlists(): List<LocalPlaylist>

    /** What was recorded about [playlistId]'s source; null when nothing was (a copy older than origins). */
    suspend fun origin(playlistId: String): ImportedOrigin?

    /**
     * Writes [update]'s songs and origin for [playlistId] in one go, [update] being handed the playlist as it is at
     * that moment; see `LocalPlaylists.applyFromOrigin`. Null when the playlist is gone.
     */
    suspend fun apply(playlistId: String, update: (LocalPlaylist) -> Pair<List<Song>, ImportedOrigin>?): LocalPlaylist?
}

/**
 * "Refresh from source": brings a library copy of a YouTube Music or Spotify playlist (or YouTube Music album) up to
 * date with its source, only when the listener asks. Reads the source; writes only to the library — never to
 * YouTube Music or Spotify. The rules are [PlaylistMerge]'s; this reads, matches and stores.
 *
 * - [preview] reads the source and says what each mode would do, without writing anything.
 * - [refresh] applies a mode, reusing the read [preview] just made (for [READ_FRESH_MS]) so a confirmation costs no
 *   second read, and records the new snapshot and when.
 * - [refreshAll] refreshes every copy that can be, one after another with a pause between reads.
 *
 * Calls for one playlist run one at a time (a second waits for the first); different playlists may run together.
 * Ids are the source's: YouTube Music video ids; Spotify track ids, a copy's song being known by its
 * [Song.spotifyId] (set by the matcher) — a song without one was added by the listener. A Spotify track is matched
 * to its YouTube Music song by [resolver] (the remembered matches first); one with no match is reported in
 * [Preview.unmatched] / [Outcome.unmatched] and not added.
 */
class PlaylistRefresher(
    private val store: RefreshStore,
    private val source: (Service) -> MusicSource,
    private val resolver: ImportResolver,
    private val connected: (Service) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val pauseMs: Long = 1_500,
    private val readFreshMs: Long = READ_FRESH_MS,
    private val matchParallelism: Int = 4,
) {
    /**
     * What a refresh would do, per mode. [firstRefresh]: no snapshot was kept, so [merge] only adds (and records
     * one). [syncedAt] when the source was last read into the copy (null: unknown).
     */
    data class Preview(
        val playlistId: String,
        val origin: PlaylistOrigin,
        val originTitle: String?,
        val syncedAt: Long?,
        val firstRefresh: Boolean,
        val merge: PlaylistMerge.Counts,
        val replace: PlaylistMerge.Counts,
        val mergeChanges: Boolean,
        val replaceChanges: Boolean,
        val unmatched: List<UnmatchedTrack>,
    )

    /** What a refresh did. [changed] false: the songs were already right (the snapshot and [syncedAt] still moved). */
    data class Outcome(
        val playlistId: String,
        val mode: PlaylistMerge.Mode,
        val counts: PlaylistMerge.Counts,
        val changed: Boolean,
        val unmatched: List<UnmatchedTrack>,
        val syncedAt: Long,
    )

    /**
     * For a playlist's status line, without reading the source. [blocked] why it cannot be refreshed right now
     * (null: it can). [localChanges] the listener's edits since the last import or refresh; null when unknown (no
     * snapshot, or no origin).
     */
    data class Status(
        val origin: PlaylistOrigin?,
        val blocked: RefreshError?,
        val originTitle: String?,
        val syncedAt: Long?,
        val localChanges: PlaylistMerge.LocalChanges?,
    )

    /** One playlist of [refreshAll]. */
    data class Item(val playlistId: String, val title: String, val result: Result<Outcome>)

    /** One read of a source, kept briefly so that a refresh confirmed after its preview does not read it again. */
    private class Read(val origin: PlaylistOrigin, val tracks: List<RemoteTrack>, val at: Long) {
        /** Spotify id to its YouTube Music song, null for no match; absent for not looked up yet. */
        val matched = HashMap<String, Song?>()
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val reads = ConcurrentHashMap<String, Read>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())

    /** Playlist ids being previewed or refreshed right now. */
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    suspend fun preview(playlistId: String): Result<Preview> = guarded(playlistId) { playlist, origin ->
        val read = read(playlist, origin, reuse = false)
        val recorded = store.origin(playlist.id)
        match(read, playlist)
        val local = locals(origin, playlist.songs)
        val remote = remotes(read)
        val base = recorded?.snapshot
        val merge = PlaylistMerge.merge(local, remote, base, PlaylistMerge.Mode.MERGE)
        val replace = PlaylistMerge.merge(local, remote, base, PlaylistMerge.Mode.REPLACE)
        Preview(
            playlistId = playlist.id,
            origin = origin,
            originTitle = recorded?.originTitle,
            syncedAt = recorded?.syncedAt,
            firstRefresh = base == null,
            merge = merge.counts,
            replace = replace.counts,
            mergeChanges = merge.changed,
            replaceChanges = replace.changed,
            unmatched = unmatched(read, (merge.unmatched + replace.unmatched).distinct().sorted(), playlist.title),
        )
    }

    suspend fun refresh(playlistId: String, mode: PlaylistMerge.Mode = PlaylistMerge.Mode.MERGE): Result<Outcome> =
        guarded(playlistId) { playlist, origin ->
            val read = read(playlist, origin, reuse = true)
            val recorded = store.origin(playlist.id)
            match(read, playlist)
            val syncedAt = now()
            val title = recorded?.originTitle ?: playlist.title
            var merged: PlaylistMerge.Result? = null
            store.apply(playlist.id) { current ->
                val result = PlaylistMerge.merge(locals(origin, current.songs), remotes(read), recorded?.snapshot, mode)
                merged = result
                val songs = result.entries.map { pick ->
                    when (pick) {
                        is PlaylistMerge.Pick.FromLocal -> current.songs[pick.index]
                        is PlaylistMerge.Pick.FromRemote -> songFor(read, pick.index, current)
                            ?: error("a song picked from the source has no match")
                    }
                }
                songs to ImportedOrigin(snapshot = result.snapshot, syncedAt = syncedAt, originTitle = title)
            } ?: throw RefreshException(RefreshError.NotFound)
            reads.remove(playlist.id)
            val result = merged ?: throw RefreshException(RefreshError.NotFound)
            Outcome(
                playlistId = playlist.id,
                mode = mode,
                counts = result.counts,
                changed = result.changed,
                unmatched = unmatched(read, result.unmatched, playlist.title),
                syncedAt = syncedAt,
            )
        }

    /** [refresh] in the refresher's own scope, so leaving the screen that asked does not stop it. */
    fun start(playlistId: String, mode: PlaylistMerge.Mode = PlaylistMerge.Mode.MERGE, onDone: (Result<Outcome>) -> Unit = {}) {
        scope.launch { onDone(refresh(playlistId, mode)) }
    }

    /**
     * Every copy [filter] keeps that has a source to refresh from, one after another, [pauseMs] between reads. A
     * service found not connected (or its session expired) is not asked again for the rest: its other copies get
     * the same answer without a request. [onProgress] gets the index about to run, the total and its title.
     */
    suspend fun refreshAll(
        mode: PlaylistMerge.Mode = PlaylistMerge.Mode.MERGE,
        filter: (LocalPlaylist) -> Boolean = { true },
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): List<Item> {
        val targets = store.playlists().filter { filter(it) && canRefresh(it.sourceId) }
        val blocked = HashMap<Service, RefreshError>()
        val out = ArrayList<Item>(targets.size)
        targets.forEachIndexed { index, playlist ->
            onProgress(index, targets.size, playlist.title)
            val service = PlaylistOrigin.of(playlist.sourceId)!!.service
            val known = blocked[service]
            val result = if (known != null) Result.failure(RefreshException(known)) else refresh(playlist.id, mode)
            val reason = (result.exceptionOrNull() as? RefreshException)?.reason
            if (reason is RefreshError.NotConnected || reason is RefreshError.Expired) blocked[service] = reason
            out += Item(playlist.id, playlist.title, result)
            // Gently: fifty playlists must not be fifty requests in a burst.
            if (known == null && index < targets.lastIndex) delay(pauseMs)
        }
        onProgress(targets.size, targets.size, "")
        return out
    }

    suspend fun status(playlistId: String): Status? {
        val playlist = store.playlist(playlistId) ?: return null
        val origin = PlaylistOrigin.of(playlist.sourceId)
        val recorded = origin?.let { store.origin(playlist.id) }
        return Status(
            origin = origin,
            blocked = blocked(playlist),
            originTitle = recorded?.originTitle,
            syncedAt = recorded?.syncedAt,
            localChanges = origin?.let { PlaylistMerge.localChanges(locals(it, playlist.songs), recorded?.snapshot) },
        )
    }

    // ---- The parts -------------------------------------------------------------------------------------------

    private suspend fun <T> guarded(playlistId: String, block: suspend (LocalPlaylist, PlaylistOrigin) -> T): Result<T> {
        val first = store.playlist(playlistId) ?: return Result.failure(RefreshException(RefreshError.NotFound))
        val origin = PlaylistOrigin.of(first.sourceId)
        blocked(first)?.let { return Result.failure(RefreshException(it)) }
        origin ?: return Result.failure(RefreshException(RefreshError.NoOrigin))
        val lock = locks.computeIfAbsent(first.id) { Mutex() }
        return lock.withLock {
            _refreshing.update { it + first.id }
            try {
                // As it is now, after whatever refresh ran before this one.
                val playlist = store.playlist(first.id) ?: throw RefreshException(RefreshError.NotFound)
                Result.success(block(playlist, origin))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RefreshException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(RefreshException(classify(origin.service, e), e))
            } finally {
                _refreshing.update { it - first.id }
            }
        }
    }

    /** Why [playlist] cannot be refreshed right now, before anything is read; null when it can. */
    private fun blocked(playlist: LocalPlaylist): RefreshError? {
        val origin = PlaylistOrigin.of(playlist.sourceId) ?: return RefreshError.NoOrigin
        return when {
            origin.isLikesList -> RefreshError.LikesList
            origin.service == Service.SPOTIFY && origin.kind == OriginKind.ALBUM -> RefreshError.NoListing
            // YouTube Music lists can be public, so they are tried without an account; Spotify needs one.
            origin.service == Service.SPOTIFY && !connected(Service.SPOTIFY) -> RefreshError.NotConnected(Service.SPOTIFY)
            else -> null
        }
    }

    private fun classify(service: Service, error: Throwable): RefreshError {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            when (cause) {
                is SpotifyException -> return when (cause.kind) {
                    SpotifyException.Kind.SIGNED_OUT -> RefreshError.NotConnected(Service.SPOTIFY)
                    SpotifyException.Kind.EXPIRED -> RefreshError.Expired(Service.SPOTIFY)
                    else -> RefreshError.Failed(Service.SPOTIFY, cause.message)
                }
                is Innertube.NotSignedInException -> return RefreshError.NotConnected(Service.YT_MUSIC)
            }
            cause = cause.cause
        }
        // A YouTube Music list that would not load without an account is, most likely, a private one.
        if (service == Service.YT_MUSIC && !connected(Service.YT_MUSIC)) return RefreshError.NotConnected(Service.YT_MUSIC)
        return RefreshError.Failed(service, error.message)
    }

    private suspend fun read(playlist: LocalPlaylist, origin: PlaylistOrigin, reuse: Boolean): Read {
        if (reuse) {
            reads[playlist.id]?.takeIf { it.origin == origin && now() - it.at in 0L until readFreshMs }?.let { return it }
        }
        val tracks = source(origin.service).playlistTracks(origin.sourceId)
        // A read of nothing is far likelier a list gone private or deleted than one emptied on purpose; taking it at
        // its word would empty the copy.
        if (tracks.isEmpty()) throw RefreshException(RefreshError.EmptySource(origin.service))
        return Read(origin, tracks, now()).also { reads[playlist.id] = it }
    }

    /** Looks up the YouTube Music song of every Spotify track the copy does not hold yet (each once). */
    private suspend fun match(read: Read, playlist: LocalPlaylist) {
        if (read.tracks.all { it.song != null }) return
        val have = playlist.songs.mapNotNullTo(HashSet()) { localKey(read.origin, it) }
        val todo = read.tracks
            .filter { it.song == null && it.remoteId !in have && it.remoteId !in read.matched }
            .distinctBy { it.remoteId }
        if (todo.isEmpty()) return
        val gate = Semaphore(matchParallelism.coerceAtLeast(1))
        val found = coroutineScope {
            todo.map { track ->
                async { gate.withPermit { track.spotify?.let { resolver.song(it) } } }
            }.awaitAll()
        }
        todo.forEachIndexed { i, track -> read.matched[track.remoteId] = found[i] }
    }

    private fun locals(origin: PlaylistOrigin, songs: List<Song>) =
        songs.map { PlaylistMerge.Local(localKey(origin, it), it.videoId) }

    private fun remotes(read: Read) = read.tracks.indices.map { j ->
        val track = read.tracks[j]
        PlaylistMerge.Remote(track.remoteId, (track.song ?: read.matched[track.remoteId])?.videoId)
    }

    /** The song to store for the source's entry [index]: its YouTube Music song, as a copy keeps it. */
    private fun songFor(read: Read, index: Int, playlist: LocalPlaylist): Song? {
        val track = read.tracks[index]
        val song = track.song ?: read.matched[track.remoteId]?.let { if (it.spotifyId == null) it.copy(spotifyId = track.remoteId) else it }
        song ?: return null
        if (read.origin.kind != OriginKind.ALBUM) return song
        // An album's songs name the album, as an imported one's do.
        val cover = playlist.coverUrl?.takeIf { it.startsWith("http") }
        return song.copy(albumName = song.albumName ?: playlist.title, thumbnailUrl = song.thumbnailUrl ?: cover)
    }

    private fun unmatched(read: Read, indexes: List<Int>, title: String) = indexes.map { j ->
        val track = read.tracks[j]
        UnmatchedTrack(track.spotify?.title ?: track.remoteId, track.spotify?.artist.orEmpty(), title)
    }

    companion object {
        /** How long a [preview]'s read is reused by the [refresh] that confirms it. */
        const val READ_FRESH_MS = 10L * 60 * 1000

        /** Whether a copy of [sourceId] has a source a refresh can read (connection aside): for showing the action. */
        fun canRefresh(sourceId: String?): Boolean {
            val origin = PlaylistOrigin.of(sourceId) ?: return false
            // Apple Music and Tidal copies are not refreshed: their sessions last only while the listener is signed in.
            if (origin.service == Service.APPLE_MUSIC || origin.service == Service.TIDAL) return false
            return !origin.isLikesList && !(origin.service == Service.SPOTIFY && origin.kind == OriginKind.ALBUM)
        }

        /** A copy's song as its source knows it: a YouTube Music video id, a Spotify track id (null: the listener's). */
        internal fun localKey(origin: PlaylistOrigin, song: Song): String? = when (origin.service) {
            Service.YT_MUSIC -> song.videoId.takeIf { it.isNotBlank() }
            Service.SPOTIFY -> song.spotifyId
            Service.APPLE_MUSIC, Service.TIDAL -> null
        }

        @Volatile private var instance: PlaylistRefresher? = null

        /** The app's refresher, over [AndroidRefreshStore] and the sources connected in the app. */
        fun get(context: Context): PlaylistRefresher = instance ?: synchronized(this) {
            instance ?: androidRefresher(context.applicationContext).also { instance = it }
        }
    }
}
