package dev.auden.transfer

import com.music.bitchord.data.spotify.SpotifyAlbum
import com.music.bitchord.data.spotify.SpotifyArtist
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.LikeListClassifier
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service

/**
 * Spotify as a [MusicSource], over [SpotifyLibrary] and the session connected in Settings. Reads only (Spotify
 * offers nothing else). Its tracks come unmatched: the import finds each one's YouTube Music song.
 *
 * Spotify's library query gives no album's track listing, so [playlistTracks] reads playlists only; an album is
 * imported as the YouTube Music album it matches (see [ImportEngine]).
 */
class SpotifySource(
    private val playlistList: suspend () -> List<SpotifyPlaylist> = { SpotifyLibrary.playlists() },
    private val albumList: suspend () -> List<SpotifyAlbum> = { SpotifyLibrary.albums() },
    private val artistList: suspend () -> List<SpotifyArtist> = { SpotifyLibrary.artists() },
    private val tracksOf: suspend (playlistId: String) -> List<SpotifyTrack> = { SpotifyLibrary.tracks(it) },
) : MusicSource {
    override val service = Service.SPOTIFY

    override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) {
        // SpotifyLibrary reads the whole list or throws; it is handed on in pages so it is stored as it is matched.
        tracksOf(SpotifyLibrary.LIKED_ID).chunked(PAGE).forEach { page -> onPage(page.map(::remote)) }
    }

    override suspend fun playlists(): List<RemoteCollection> =
        playlistList().filterNot { LikeListClassifier.isLikesList(Service.SPOTIFY, it.id) }.map {
            RemoteCollection(PlaylistOrigin.SPOTIFY_PLAYLIST + it.id, service, OriginKind.PLAYLIST, it.name, it.owner.orEmpty(), it.imageUrl)
        }.distinctBy { it.sourceId }

    override suspend fun albums(): List<RemoteCollection> =
        albumList().map {
            RemoteCollection(PlaylistOrigin.SPOTIFY_ALBUM + it.id, service, OriginKind.ALBUM, it.name, it.artist, it.imageUrl)
        }.distinctBy { it.sourceId }

    override suspend fun artists(): List<RemoteArtist> =
        artistList().map { RemoteArtist(it.id, service, it.name, it.imageUrl) }.distinctBy { it.id }

    override suspend fun playlistTracks(sourceId: String): List<RemoteTrack> {
        val origin = PlaylistOrigin.of(sourceId)?.takeIf { it.service == Service.SPOTIFY }
            ?: throw IllegalArgumentException("not a Spotify list: $sourceId")
        if (origin.kind != OriginKind.PLAYLIST && origin.kind != OriginKind.LIKES) {
            throw UnsupportedOperationException("Spotify gives no album listing: $sourceId")
        }
        return tracksOf(origin.remoteId).map(::remote)
    }

    private fun remote(track: SpotifyTrack) = RemoteTrack(track.id, spotify = track)

    private companion object {
        const val PAGE = 100
    }
}
