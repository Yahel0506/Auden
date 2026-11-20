package dev.auden.transfer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.OriginKind
import dev.auden.library.Service

/**
 * One song as a source lists it. [remoteId] is its id there (a YouTube Music video id, a Spotify track id) —
 * what a copy's snapshot is made of. [song] is ready to play (YouTube Music); a Spotify track has none until it
 * has been matched to one, from [spotify].
 */
data class RemoteTrack(
    val remoteId: String,
    val song: Song? = null,
    val spotify: SpotifyTrack? = null,
)

/**
 * A playlist or album in a source's library. [sourceId] is in the shape library copies are saved under (see
 * [dev.auden.library.PlaylistOrigin]): `PL…`/`MPREb_…` for YouTube Music, `spotify:playlist:<id>` and
 * `spotify:album:<id>` for Spotify.
 */
data class RemoteCollection(
    val sourceId: String,
    val service: Service,
    val kind: OriginKind,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
)

/** An artist followed (Spotify) or subscribed to (YouTube Music). [id] is its id on [service]. */
data class RemoteArtist(
    val id: String,
    val service: Service,
    val name: String,
    val thumbnailUrl: String? = null,
)

/** The read of a list stopped before its end: a page failed, or the account changed under it. */
class IncompleteReadException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * A library Auden can import from: reads only, never a write. Thin on purpose — one per service, no registry —
 * wrapping the clients that already exist ([YtMusicSource], [SpotifySource]).
 *
 * Every list is read whole or not at all: a source that cannot finish one throws rather than handing back the
 * part it got, so an import never mistakes a short read for the real thing.
 */
interface MusicSource {
    val service: Service

    /**
     * The liked songs, newest first, a page at a time to [onPage] — so a long list is stored as it arrives.
     * Returns only once the last page has been delivered; throws (an [IncompleteReadException], or whatever
     * [onPage] threw) otherwise.
     */
    suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit)

    /** The playlists, without the liked-songs list (that is [likedSongs]). */
    suspend fun playlists(): List<RemoteCollection>

    suspend fun albums(): List<RemoteCollection>

    suspend fun artists(): List<RemoteArtist>

    /** The podcast shows followed there, in the shape of an artist: a name, a picture and the page that opens it. */
    suspend fun podcasts(): List<RemoteArtist> = emptyList()

    /** Every track of the playlist or album [sourceId] (a [RemoteCollection.sourceId]), in order. */
    suspend fun playlistTracks(sourceId: String): List<RemoteTrack>
}
