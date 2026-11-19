package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.library_podcasts
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ReplayCardRowSkeleton
import com.music.bitchord.ui.replay.ReplayCardRow
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/**
 * The phone's folders, drawn as a list under the Replay cards: Downloads and
 * Local Music, the song cache when Settings has it shown, and the two remote
 * libraries it can reach.
 */
@Composable
fun libraryLinks(
    /** The account's shelves, as the Library has them: the playlists, and the artists, albums and the rest. */
    shelves: List<com.music.bitchord.data.model.HomeShelf>,
    /** What is kept on this device as lists of its own, for the Playlists section to hold with the others. */
    deviceItems: List<ShelfItem>,
    onOpenShelf: (com.music.bitchord.data.model.HomeShelf) -> Unit,
): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    fun link(title: String, browseId: String, icon: ImageVector = deviceFolderIcon(browseId) ?: Icons.Rounded.Cloud) = LibraryLink(
        item = ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId),
        icon = icon,
    )
    fun action(id: String, title: String, icon: ImageVector, onClick: () -> Unit) = LibraryLink(
        item = ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = null),
        icon = icon,
        id = id,
        onClick = onClick,
    )
    // What the "Other" page held, each as a row: the account's playlists, then every shelf it had.
    val spotifyItems = rememberSpotifyShelf()
    fun shelfRow(id: String, shelfTitle: String, title: String, icon: ImageVector, always: Boolean = false): LibraryLink? {
        val base = shelves.firstOrNull { it.title == shelfTitle }
        // One section, every source: the account's own, Spotify's and what is kept on this device, each marked with where it
        // comes from (the device's by having no service).
        val items = base?.items.orEmpty().map { it.copy(platform = ShelfItem.PLATFORM_YT_MUSIC) } +
            if (shelfTitle == com.music.bitchord.data.YtMusicRepository.PLAYLISTS_SHELF) {
                spotifyItems.map { it.copy(platform = ShelfItem.PLATFORM_SPOTIFY) } + deviceItems
            } else {
                emptyList()
            }
        if (items.isEmpty() && !(always && shelves.isNotEmpty())) return null
        val merged = com.music.bitchord.data.model.HomeShelf(title, items)
        return action(id, title, icon) { onOpenShelf(merged) }
    }
    val playlistsTitle = stringResource(R.string.playlists)
    val artistsTitle = stringResource(R.string.artists)
    val albumsTitle = stringResource(R.string.albums)
    val podcastsTitle = org.jetbrains.compose.resources.stringResource(Res.string.library_podcasts)
    val subscriptionsTitle = stringResource(R.string.subscriptions)
    val order by AppSettings.libraryLinkOrder.collectAsStateWithLifecycle()
    // Plain text rows into each section; the account's artists, albums and podcasts are behind "Other".
    return listOfNotNull(
        link(stringResource(R.string.downloads), "local:downloads", SfIcons.Download),
        link(stringResource(R.string.local_music), "local:all", SfIcons.MusicNote),
        link(stringResource(R.string.cached_songs), CACHE_FOLDER_BROWSE_ID),
        // Only once set up in Sources; unconfigured, they'd just be dead ends.
        link(stringResource(R.string.webdav), com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            .takeIf { webdavConfigured.isNotBlank() },
        link(stringResource(R.string.smb), com.music.bitchord.data.smb.SmbConfig.BROWSE_ID)
            .takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
        shelfRow("shelf:playlists", com.music.bitchord.data.YtMusicRepository.PLAYLISTS_SHELF, playlistsTitle, SfIcons.PlaylistPlay, always = true),
        shelfRow("shelf:artists", "Artists", artistsTitle, SfIcons.Artist),
        shelfRow("shelf:albums", "Albums", albumsTitle, SfIcons.Album),
        shelfRow("shelf:podcasts", "Podcasts", podcastsTitle, SfIcons.Radio),
        shelfRow("shelf:subscriptions", "Subscriptions", subscriptionsTitle, SfIcons.Groups),
    ).let { all ->
        // In the order the editing left them in; what it has not heard of stands after them, as it came.
        all.sortedBy { link -> order.indexOf(link.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}

/**
 * The phone's "On device" shelf: the playlists and albums downloaded whole —
 * the same promise the folders above it make, here, now, without a network.
 * Nothing is truncated: the shelf is a row that scrolls, so "all of them"
 * costs nothing.
 */
@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    // "Mis favoritas": what is liked on this device, first among the lists, once there is anything in it.
    val deviceLiked by com.music.bitchord.data.OfflinePlaylists.liked.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val favoritesTitle = stringResource(R.string.my_favorites)
    val favorites = if (deviceLiked.isEmpty()) emptyList() else listOf(
        ShelfItem(
            title = favoritesTitle,
            subtitle = "",
            thumbnailUrl = androidx.compose.runtime.remember { com.music.bitchord.data.FavoritesCover.uri(context) },
            videoId = null,
            browseId = com.music.bitchord.data.OfflinePlaylists.PREFIX + com.music.bitchord.data.OfflinePlaylists.LIKED_ID,
        ),
    )
    return favorites + downloadedReleases.map { release ->
        ShelfItem(
            title = release.title,
            // The credit the release was downloaded with, because this is also
            // what the page it opens bills itself by — see `headerLines`, which
            // reads the kind and the owner back out of it. Saying "Downloaded
            // playlist" here instead would make that header read "Downloaded
            // playlist" over "PLAYLIST • 12 SONGS", and the shelf this card is on
            // already says where it lives.
            subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
}

/**
 * The glyph a device folder is drawn with — its row on the Library page, and
 * the placeholder cover its own page opens on. Null for anything that isn't
 * one of the folders.
 */
fun deviceFolderIcon(browseId: String): ImageVector? = when (browseId) {
    "local:downloads" -> Icons.Rounded.Download
    "local:all" -> Icons.Rounded.Folder
    CACHE_FOLDER_BROWSE_ID -> Icons.Rounded.Storage
    com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID -> Icons.Rounded.Cloud
    com.music.bitchord.data.smb.SmbConfig.BROWSE_ID -> Icons.Rounded.Lan
    else -> null
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"
