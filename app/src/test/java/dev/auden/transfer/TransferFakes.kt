package dev.auden.transfer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.ImportedOrigin
import dev.auden.library.Service
import dev.auden.library.db.FollowedArtistEntity

internal fun song(id: String, spotify: String? = null) = Song(videoId = id, title = "t-$id", artist = "a", thumbnailUrl = null, spotifyId = spotify)

internal fun spotifyTrack(id: String) = SpotifyTrack(id = id, title = "s-$id", artist = "sa", album = null, durationMs = 0, imageUrl = null)

/** The stores, in memory, with OfflinePlaylists' rules for likes (by video id, after what is there, in order). */
internal class FakeLibrary : ImportLibrary {
    val likes = mutableListOf<Song>()
    val hidden = mutableSetOf<String>()
    val copies = linkedMapOf<String, Copy>()
    val artists = linkedMapOf<String, FollowedArtistEntity>()
    var appendCalls = 0

    data class Copy(val id: String, val sourceId: String, val title: String, val songs: List<Song>, val cover: String?, val origin: ImportedOrigin)

    override fun hiddenLikes(): Set<String> = hidden

    override fun appendLikes(songs: List<Song>): Int {
        appendCalls++
        val known = likes.mapTo(HashSet()) { it.videoId }
        val fresh = songs.filter { it.videoId.isNotBlank() && known.add(it.videoId) }
        likes += fresh
        return fresh.size
    }

    override fun copyOf(sourceId: String): String? = copies.values.firstOrNull { it.sourceId == sourceId }?.id

    override fun createCopy(sourceId: String, title: String, songs: List<Song>, coverUrl: String?, origin: ImportedOrigin): String {
        val id = "copy${copies.size + 1}"
        copies[id] = Copy(id, sourceId, title, songs.distinctBy { it.videoId }, coverUrl, origin)
        return id
    }

    override suspend fun followedArtistIds(): Set<String> = artists.keys

    override suspend fun follow(artists: List<FollowedArtistEntity>): Int =
        artists.count { this.artists.putIfAbsent(it.id, it) == null }
}

/** Matches a Spotify track to `yt-<id>` unless it is in [misses]. */
internal class FakeResolver(
    private val misses: Set<String> = emptySet(),
    private val albums: Map<String, RemoteCollection> = emptyMap(),
    private val artistIds: Map<String, String> = emptyMap(),
) : ImportResolver {
    override suspend fun song(track: SpotifyTrack): Song? = if (track.id in misses) null else song("yt-${track.id}", spotify = track.id)

    override suspend fun ytAlbum(album: RemoteCollection): RemoteCollection? = albums[album.sourceId]

    override suspend fun ytArtistId(artist: RemoteArtist): String? = artistIds[artist.id]
}

/**
 * A source whose liked songs come in [likePages]; [failAtPage] throws when that page is reached, once per run
 * left in [failuresLeft].
 */
internal class FakeSource(
    override val service: Service,
    var likePages: List<List<RemoteTrack>> = emptyList(),
    var failAtPage: Int? = null,
    var failuresLeft: Int = 1,
    val playlistList: List<RemoteCollection> = emptyList(),
    val albumList: List<RemoteCollection> = emptyList(),
    val artistList: List<RemoteArtist> = emptyList(),
    val tracks: Map<String, List<RemoteTrack>> = emptyMap(),
) : MusicSource {
    var likeReads = 0
    val tracksRead = mutableListOf<String>()

    override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) {
        likeReads++
        likePages.forEachIndexed { index, page ->
            if (index == failAtPage && failuresLeft > 0) {
                failuresLeft--
                throw IncompleteReadException("page $index failed")
            }
            onPage(page)
        }
    }

    override suspend fun playlists() = playlistList
    override suspend fun albums() = albumList
    override suspend fun artists() = artistList

    override suspend fun playlistTracks(sourceId: String): List<RemoteTrack> {
        tracksRead += sourceId
        return tracks[sourceId] ?: throw IncompleteReadException("no such list $sourceId")
    }
}

internal fun yt(vararg ids: String) = ids.map { RemoteTrack(it, song = song(it)) }

internal fun sp(vararg ids: String) = ids.map { RemoteTrack(it, spotify = spotifyTrack(it)) }
