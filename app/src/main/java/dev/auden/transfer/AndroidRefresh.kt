package dev.auden.transfer

import android.content.Context
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.SpotifyLibrary
import dev.auden.library.ImportedOrigin
import dev.auden.library.LocalPlaylists
import dev.auden.library.PlaylistSnapshot
import dev.auden.library.Service

/** [RefreshStore] over [LocalPlaylists] and its `playlist_origin` rows. */
class AndroidRefreshStore : RefreshStore {
    override suspend fun playlist(id: String): LocalPlaylist? {
        LocalPlaylists.awaitLoaded()
        return LocalPlaylists.get(id)
    }

    override suspend fun playlists(): List<LocalPlaylist> {
        LocalPlaylists.awaitLoaded()
        return LocalPlaylists.playlists.value
    }

    override suspend fun origin(playlistId: String): ImportedOrigin? =
        LocalPlaylists.originOf(playlistId)?.let { ImportedOrigin(PlaylistSnapshot.decode(it.snapshot), it.syncedAt, it.originTitle) }

    override suspend fun apply(playlistId: String, update: (LocalPlaylist) -> Pair<List<Song>, ImportedOrigin>?): LocalPlaylist? =
        LocalPlaylists.applyFromOrigin(playlistId, update)
}

/** The app's [PlaylistRefresher]: the two sources, the existing matcher, and the sign-ins as they are when asked. */
internal fun androidRefresher(app: Context): PlaylistRefresher {
    val yt = YtMusicSource()
    val spotify = SpotifySource()
    return PlaylistRefresher(
        store = AndroidRefreshStore(),
        source = { service -> if (service == Service.SPOTIFY) spotify else yt },
        resolver = AndroidImportResolver(app),
        connected = { service ->
            when (service) {
                Service.SPOTIFY -> SpotifyLibrary.isSignedIn()
                Service.YT_MUSIC -> Innertube.cookie != null
            }
        },
    )
}
