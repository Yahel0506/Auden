package dev.auden.transfer

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.YtMusicRepository.SongPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import dev.auden.library.LikeListClassifier
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service

/**
 * YouTube Music as a [MusicSource], over [YtMusicRepository] and whatever account it is signed in as. Reads
 * only. The repository calls are parameters so the paging can be tested without a network.
 */
class YtMusicSource(
    private val firstPage: suspend (browseId: String) -> Result<SongPage> = { YtMusicRepository.browseSongs(it) },
    private val nextPage: suspend (token: String) -> Result<SongPage> = { YtMusicRepository.moreSongs(it) },
    private val playlistCards: suspend () -> Result<List<ShelfItem>> = { YtMusicRepository.libraryPlaylistCards() },
    private val albumCards: suspend () -> Result<List<ShelfItem>> = { YtMusicRepository.libraryAlbums() },
    private val subscriptionCards: suspend () -> Result<List<ShelfItem>> = { YtMusicRepository.librarySubscriptions() },
) : MusicSource {
    override val service = Service.YT_MUSIC

    override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) =
        walk(YtMusicRepository.LIKED_MUSIC) { songs -> onPage(songs.map { RemoteTrack(it.videoId, song = it) }) }

    override suspend fun playlists(): List<RemoteCollection> =
        playlistCards().getOrThrow().mapNotNull { card ->
            val origin = PlaylistOrigin.of(card.browseId) ?: return@mapNotNull null
            if (origin.service != Service.YT_MUSIC || LikeListClassifier.isLikesList(Service.YT_MUSIC, origin.remoteId)) return@mapNotNull null
            // A feed (FEmusic_…) or a channel (UC…) card is not a list of songs to copy.
            if (origin.remoteId.startsWith("FE") || origin.remoteId.startsWith("UC")) return@mapNotNull null
            RemoteCollection(origin.sourceId, service, origin.kind, card.title, card.subtitle, card.thumbnailUrl)
        }.distinctBy { it.sourceId }

    override suspend fun albums(): List<RemoteCollection> =
        albumCards().getOrThrow().mapNotNull { card ->
            val origin = PlaylistOrigin.of(card.browseId)?.takeIf { it.service == Service.YT_MUSIC && !it.isLikesList } ?: return@mapNotNull null
            RemoteCollection(origin.sourceId, service, OriginKind.ALBUM, card.title, card.subtitle, card.thumbnailUrl)
        }.distinctBy { it.sourceId }

    override suspend fun artists(): List<RemoteArtist> =
        subscriptionCards().getOrThrow().mapNotNull { card ->
            val id = card.browseId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemoteArtist(id, service, card.title, card.thumbnailUrl)
        }.distinctBy { it.id }

    override suspend fun playlistTracks(sourceId: String): List<RemoteTrack> {
        val origin = PlaylistOrigin.of(sourceId)?.takeIf { it.service == Service.YT_MUSIC }
            ?: throw IllegalArgumentException("not a YouTube Music list: $sourceId")
        val out = mutableListOf<RemoteTrack>()
        walk(origin.browseId) { songs -> songs.mapTo(out) { RemoteTrack(it.videoId, song = it) } }
        return out
    }

    /**
     * Every page of [browseId], each song once, in order. Unbounded on purpose — a long Liked Music is exactly
     * what has to be read to its end — but it stops at a token it has seen, or a page whose songs were all seen
     * already (a feed that has looped back on itself, as PlaylistSync found). A failed page throws.
     */
    internal suspend fun walk(browseId: String, onPage: suspend (List<Song>) -> Unit) {
        val opening = firstPage(browseId).getOrElse { throw IncompleteReadException("$browseId: first page failed", it) }
        val seen = HashSet<String>()
        fun fresh(songs: List<Song>) = songs.filter { it.videoId.isNotBlank() && seen.add(it.videoId) }
        fresh(opening.songs).takeIf { it.isNotEmpty() }?.let { onPage(it) }
        val tokens = HashSet<String>()
        var next = opening.continuation
        while (next != null && tokens.add(next)) {
            val page = nextPage(next).getOrElse { throw IncompleteReadException("$browseId: a page failed", it) }
            val songs = fresh(page.songs)
            // A page of songs all seen before is a feed that looped back; a page that parsed to nothing is not the end
            // (an old Liked Music can hold pages of items the parser drops): its token, if it has one, is followed.
            if (page.songs.isNotEmpty() && songs.isEmpty()) break
            if (songs.isNotEmpty()) onPage(songs)
            next = page.continuation
        }
    }
}
