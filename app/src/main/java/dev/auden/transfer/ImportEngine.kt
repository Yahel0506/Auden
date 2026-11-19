package dev.auden.transfer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.ImportedOrigin
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service
import dev.auden.library.db.FollowedArtistEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Where an import writes: Auden's likes, its playlists and its followed artists. See [AndroidImportLibrary]. */
interface ImportLibrary {
    /** Video ids un-liked here that an account still likes. An import never brings them back. */
    fun hiddenLikes(): Set<String>

    /** Adds [songs] after the likes already there, in order, skipping what is liked; returns how many went in. */
    fun appendLikes(songs: List<Song>): Int

    /** The id of the library copy saved from [sourceId], if there is one. */
    fun copyOf(sourceId: String): String?

    /** A new library copy of [sourceId], with [origin] recorded; returns its id. */
    fun createCopy(sourceId: String, title: String, songs: List<Song>, coverUrl: String?, origin: ImportedOrigin): String

    suspend fun followedArtistIds(): Set<String>

    /** Follows the artists not already followed; returns how many were new. */
    suspend fun follow(artists: List<FollowedArtistEntity>): Int
}

/** What an import has to look up elsewhere. See [AndroidImportResolver]. */
interface ImportResolver {
    /** The YouTube Music song a Spotify track is, or null when there is none. */
    suspend fun song(track: SpotifyTrack): Song?

    /** The YouTube Music album a Spotify album is (as a YouTube Music [RemoteCollection]), or null. */
    suspend fun ytAlbum(album: RemoteCollection): RemoteCollection?

    /** The YouTube Music channel id of a Spotify artist, or null when none was found. */
    suspend fun ytArtistId(artist: RemoteArtist): String?
}

enum class ImportStep { LIKES, PLAYLIST, ALBUM, ARTISTS }

enum class ImportStage { READING, MATCHING, STORING }

/**
 * Where an import is, for a progress bar or a [dev.auden.library.JobNotification]. [title] names the playlist or
 * album being read; [total] 0 means "not known yet" (an indeterminate bar).
 */
data class ImportProgress(val step: ImportStep, val stage: ImportStage, val title: String?, val done: Int, val total: Int)

/** How one category went: [added] new, [skipped] already there (or un-liked here), [unmatched] not found on YouTube Music. */
data class Tally(val added: Int = 0, val skipped: Int = 0, val unmatched: Int = 0) {
    operator fun plus(other: Tally) = Tally(added + other.added, skipped + other.skipped, unmatched + other.unmatched)
}

/** A song that was not imported because nothing on YouTube Music matched it; [from] is its playlist, null for likes. */
data class UnmatchedTrack(val title: String, val artist: String, val from: String?)

/** Something that could not be read at all; [title] is the playlist or album, null for a whole category. */
data class FailedItem(val step: ImportStep, val title: String?, val reason: String?)

/**
 * What an import did. [created] holds the ids of the library copies it made, in order, for a screen to open.
 * Albums matched to nothing count in [albums] as unmatched.
 */
data class ImportReport(
    val likes: Tally = Tally(),
    val playlists: Tally = Tally(),
    val albums: Tally = Tally(),
    val artists: Tally = Tally(),
    val unmatched: List<UnmatchedTrack> = emptyList(),
    val failed: List<FailedItem> = emptyList(),
    val created: List<String> = emptyList(),
) {
    val complete: Boolean get() = failed.isEmpty()

    operator fun plus(other: ImportReport) = ImportReport(
        likes + other.likes, playlists + other.playlists, albums + other.albums, artists + other.artists,
        unmatched + other.unmatched, failed + other.failed, created + other.created,
    )
}

/** What to bring over from one source. */
data class ImportSelection(
    val likes: Boolean = false,
    val collections: List<RemoteCollection> = emptyList(),
    val artists: List<RemoteArtist> = emptyList(),
)

/**
 * Brings a source's library into Auden's, once. Reads from the source, writes only to Auden: likes into Auden's
 * likes, a playlist or album into a library copy that records where it came from, an artist into the followed
 * artists. Nothing is ever written back to the service.
 *
 * - **Idempotent.** A song already liked, a source already copied, an artist already followed is skipped, so
 *   running the same import twice — or after one that stopped halfway — adds only what is missing. An existing
 *   copy is left as it is: bringing a copy up to date is "refresh from source", not this.
 * - **Likes keep their order**, after the likes made here, and are written a page at a time.
 * - **The liked-songs list is never a playlist**: handed one, the import takes it as likes.
 * - **Spotify** songs need a YouTube Music match; those without one are reported in [ImportReport.unmatched]
 *   and not imported. A Spotify album is copied as the YouTube Music album it matches, or reported unmatched.
 *
 * Pure: everything it touches comes in through [library], [resolver] and the sources it is given.
 */
class ImportEngine(
    private val library: ImportLibrary,
    private val resolver: ImportResolver,
    /** Reads the YouTube Music album a Spotify album was matched to. */
    private val ytCatalog: MusicSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val matchParallelism: Int = 4,
) {
    /** Everything in [selection], likes first; a part that fails is reported and the rest still runs. */
    suspend fun import(source: MusicSource, selection: ImportSelection, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        var report = ImportReport()
        if (selection.likes) report += importLikes(source, onProgress)
        selection.collections.forEach { report += importCollection(source, it, onProgress) }
        if (selection.artists.isNotEmpty()) report += importArtists(source, selection.artists, onProgress)
        return report
    }

    /** The source's liked songs into Auden's likes. Pages already stored stay stored if a later one fails. */
    suspend fun importLikes(source: MusicSource, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        var tally = Tally()
        val unmatched = mutableListOf<UnmatchedTrack>()
        val hidden = library.hiddenLikes()
        var read = 0
        val failure = attempt {
            onProgress(ImportProgress(ImportStep.LIKES, ImportStage.READING, null, 0, 0))
            source.likedSongs { page ->
                val (songs, missed) = playable(page, null) { done ->
                    onProgress(ImportProgress(ImportStep.LIKES, ImportStage.MATCHING, null, read + done, 0))
                }
                read += page.size
                unmatched += missed
                val wanted = songs.filter { it.videoId !in hidden }
                val added = library.appendLikes(wanted)
                tally += Tally(added = added, skipped = songs.size - added, unmatched = missed.size)
                onProgress(ImportProgress(ImportStep.LIKES, ImportStage.STORING, null, read, 0))
            }
        }
        return ImportReport(
            likes = tally,
            unmatched = unmatched,
            failed = listOfNotNull(failure?.let { FailedItem(ImportStep.LIKES, null, it) }),
        )
    }

    /** A copy of one playlist or album. Already copied: skipped, and the copy left alone. */
    suspend fun importCollection(source: MusicSource, collection: RemoteCollection, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        if (PlaylistOrigin.of(collection.sourceId)?.isLikesList == true || collection.kind == OriginKind.LIKES) {
            return importLikes(source, onProgress)
        }
        val step = if (collection.kind == OriginKind.ALBUM) ImportStep.ALBUM else ImportStep.PLAYLIST
        fun tally(t: Tally) = if (step == ImportStep.ALBUM) ImportReport(albums = t) else ImportReport(playlists = t)

        var unmatched = emptyList<UnmatchedTrack>()
        var result: ImportReport? = null
        val failure = attempt {
            onProgress(ImportProgress(step, ImportStage.READING, collection.title, 0, 0))
            // Spotify lists no album's songs: the album is copied as the YouTube Music one it matches.
            val (target, reader) = if (collection.service == Service.SPOTIFY && collection.kind == OriginKind.ALBUM) {
                val match = resolver.ytAlbum(collection)
                if (match == null) {
                    result = tally(Tally(unmatched = 1))
                    return@attempt
                }
                match to ytCatalog
            } else {
                collection to source
            }
            if (library.copyOf(target.sourceId) != null) {
                result = tally(Tally(skipped = 1))
                return@attempt
            }
            val tracks = reader.playlistTracks(target.sourceId)
            val (songs, missed, taken) = playable(tracks, collection.title) { done ->
                onProgress(ImportProgress(step, ImportStage.MATCHING, collection.title, done, tracks.size))
            }
            unmatched = missed
            val filled = if (target.kind == OriginKind.ALBUM) {
                songs.map { it.copy(albumName = it.albumName ?: target.title, thumbnailUrl = it.thumbnailUrl ?: target.thumbnailUrl) }
            } else {
                songs
            }
            onProgress(ImportProgress(step, ImportStage.STORING, collection.title, filled.size, filled.size))
            val id = library.createCopy(
                target.sourceId,
                target.title,
                filled,
                target.thumbnailUrl,
                // What the copy took in, not every id listed: a track that found no match is not "removed by the
                // listener" to a later refresh (see PlaylistMerge), so it is added once a match is found.
                ImportedOrigin(snapshot = taken, syncedAt = now(), originTitle = target.title),
            )
            result = tally(Tally(added = 1)).copy(created = listOf(id))
        }
        if (failure != null) return ImportReport(failed = listOf(FailedItem(step, collection.title, failure)))
        return (result ?: ImportReport()).copy(unmatched = unmatched)
    }

    /** [artists] (all the source follows, when null) into Auden's followed artists. */
    suspend fun importArtists(source: MusicSource, artists: List<RemoteArtist>? = null, onProgress: (ImportProgress) -> Unit = {}): ImportReport {
        var tally = Tally()
        val failure = attempt {
            onProgress(ImportProgress(ImportStep.ARTISTS, ImportStage.READING, null, 0, 0))
            val wanted = artists ?: source.artists()
            val followed = library.followedArtistIds().toHashSet()
            val rows = mutableListOf<FollowedArtistEntity>()
            wanted.forEachIndexed { index, artist ->
                onProgress(ImportProgress(ImportStep.ARTISTS, ImportStage.MATCHING, null, index, wanted.size))
                val id = when (artist.service) {
                    Service.YT_MUSIC -> artist.id
                    // Kept under its Spotify id when no YouTube Music page was found: still followed, by name.
                    Service.SPOTIFY -> resolver.ytArtistId(artist) ?: "spotify:artist:${artist.id}"
                }
                if (!followed.add(id)) return@forEachIndexed
                rows += FollowedArtistEntity(
                    id = id,
                    name = artist.name,
                    thumb = artist.thumbnailUrl,
                    originService = artist.service.name,
                    originId = artist.id,
                    // Newest first, as the source listed them: the first gets the latest time.
                    addedAt = now() - index,
                )
            }
            onProgress(ImportProgress(ImportStep.ARTISTS, ImportStage.STORING, null, wanted.size, wanted.size))
            val added = if (rows.isEmpty()) 0 else library.follow(rows)
            tally = Tally(added = added, skipped = wanted.size - added)
        }
        return ImportReport(artists = tally, failed = listOfNotNull(failure?.let { FailedItem(ImportStep.ARTISTS, null, it) }))
    }

    /**
     * The songs of a library copy of a service's liked list (see `LocalPlaylists.likesListCopies`) into the likes.
     * The copy itself is left for the listener to delete.
     */
    fun likesFromCopy(songs: List<Song>): ImportReport {
        val wanted = songs.filter { it.videoId !in library.hiddenLikes() }
        val added = library.appendLikes(wanted)
        return ImportReport(likes = Tally(added = added, skipped = songs.size - added))
    }

    /** [songs] what [taken] (remote ids, in order) became; [missed] what found no match. */
    private data class Playable(val songs: List<Song>, val missed: List<UnmatchedTrack>, val taken: List<String>)

    /**
     * [tracks] as songs, in their order: YouTube Music's as they are, Spotify's matched (a few at a time, which is
     * what the search tolerates).
     */
    private suspend fun playable(
        tracks: List<RemoteTrack>,
        from: String?,
        onMatched: (Int) -> Unit,
    ): Playable {
        if (tracks.none { it.song == null }) return Playable(tracks.mapNotNull { it.song }, emptyList(), tracks.map { it.remoteId })
        val gate = Semaphore(matchParallelism.coerceAtLeast(1))
        val done = AtomicInteger()
        val found = coroutineScope {
            tracks.map { track ->
                async {
                    track.song ?: gate.withPermit {
                        val spotify = track.spotify
                        val song = spotify?.let { resolver.song(it) }
                        onMatched(done.incrementAndGet())
                        song
                    }
                }
            }.awaitAll()
        }
        val songs = mutableListOf<Song>()
        val missed = mutableListOf<UnmatchedTrack>()
        val taken = mutableListOf<String>()
        tracks.forEachIndexed { index, track ->
            val song = found[index]
            if (song != null) {
                songs += song
                taken += track.remoteId
            } else {
                missed += UnmatchedTrack(track.spotify?.title ?: track.remoteId, track.spotify?.artist.orEmpty(), from)
            }
        }
        return Playable(songs, missed, taken)
    }

    /** Runs [block]; a failure comes back as its message (never null), cancellation goes on up. */
    private suspend fun attempt(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
