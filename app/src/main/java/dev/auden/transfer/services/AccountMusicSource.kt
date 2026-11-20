package dev.auden.transfer.services

import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.OriginKind
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service
import dev.auden.transfer.IncompleteReadException
import dev.auden.transfer.MusicSource
import dev.auden.transfer.RemoteArtist
import dev.auden.transfer.RemoteCollection
import dev.auden.transfer.RemoteTrack

/**
 * A signed-in [AccountLibrary] (Apple Music, Tidal) as a [MusicSource]. Reads only. Its tracks come unmatched, as
 * Spotify's do: the import finds each one's YouTube Music song from its title, artist, album and length (the shape
 * [SpotifyTrack] already has for that).
 */
class AccountMusicSource(override val service: Service, private val library: AccountLibrary) : MusicSource {
    private val playlistPrefix = if (service == Service.APPLE_MUSIC) PlaylistOrigin.APPLE_PLAYLIST else PlaylistOrigin.TIDAL_PLAYLIST
    private val albumPrefix = if (service == Service.APPLE_MUSIC) PlaylistOrigin.APPLE_ALBUM else PlaylistOrigin.TIDAL_ALBUM

    override suspend fun likedSongs(onPage: suspend (List<RemoteTrack>) -> Unit) {
        // The library reads the whole list or throws; it is handed on in pages so it is stored as it is matched.
        guard { library.likedTracks() }.chunked(PAGE).forEach { page -> onPage(page.map(::remote)) }
    }

    override suspend fun playlists(): List<RemoteCollection> =
        guard { library.playlists() }.map { RemoteCollection(playlistPrefix + it.id, service, OriginKind.PLAYLIST, it.name, it.subtitle, it.coverUrl) }
            .distinctBy { it.sourceId }

    override suspend fun albums(): List<RemoteCollection> =
        guard { library.albums() }.map { RemoteCollection(albumPrefix + it.id, service, OriginKind.ALBUM, it.title, it.artist, it.coverUrl) }
            .distinctBy { it.sourceId }

    override suspend fun artists(): List<RemoteArtist> =
        guard { library.artists() }.map { RemoteArtist(it.id, service, it.name, it.imageUrl) }.distinctBy { it.id }

    override suspend fun playlistTracks(sourceId: String): List<RemoteTrack> {
        val origin = PlaylistOrigin.of(sourceId)?.takeIf { it.service == service }
            ?: throw IllegalArgumentException("not a ${service.name} list: $sourceId")
        val tracks = guard { if (origin.kind == OriginKind.ALBUM) library.albumTracks(origin.remoteId) else library.playlistTracks(origin.remoteId) }
        return tracks.map(::remote)
    }

    private fun remote(t: ServiceTrack) = RemoteTrack(
        remoteId = "${service.name.lowercase()}:${t.id}",
        spotify = SpotifyTrack(t.id, t.title, t.artist, t.album, (t.durationMs ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), t.imageUrl),
    )

    /** A failed read is an incomplete one: what the import is told, so it never mistakes part of a list for the whole. */
    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: AccountImportException) {
        throw IncompleteReadException(e.message ?: "The read stopped", e)
    }

    private companion object { const val PAGE = 100 }
}
