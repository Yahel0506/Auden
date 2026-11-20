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
    /** What is kept on this device as lists of its own — made here or brought over — for the Playlists section. */
    deviceItems: List<ShelfItem>,
    /** The albums kept on this device: brought over and saved, or downloaded whole. */
    albumItems: List<ShelfItem>,
    onOpenShelf: (com.music.bitchord.data.model.HomeShelf) -> Unit,
    /** A device folder opened from its row — told apart from a card, which brings its own cover along. */
    onOpenFolder: (ShelfItem) -> Unit = {},
): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    fun link(title: String, browseId: String, icon: ImageVector = deviceFolderIcon(browseId) ?: Icons.Rounded.Cloud): LibraryLink {
        // The folders are special playlists, each with a cover of its own (see FolderCover), so none rides along here.
        val item = ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId)
        return LibraryLink(
            item = item,
            icon = icon,
            onClick = if (com.music.bitchord.ui.components.folderCoverStyle(browseId) != null) ({ onOpenFolder(item) }) else null,
        )
    }
    fun action(id: String, title: String, icon: ImageVector, onClick: () -> Unit) = LibraryLink(
        item = ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = null),
        icon = icon,
        id = id,
        onClick = onClick,
    )
    // The Library is Auden's own: each section holds what is on this device or was brought over with "Transfer
    // your music", never what an account holds live. Where an item came from is told by its logo.
    val followed by dev.auden.library.FollowedArtists.items(androidx.compose.ui.platform.LocalContext.current)
        .collectAsStateWithLifecycle(emptyList())
    val artistItems = followed.filterNot { it.browseId?.startsWith(PODCAST_PREFIX) == true }
    val podcastItems = followed.filter { it.browseId?.startsWith(PODCAST_PREFIX) == true }
    fun sectionRow(id: String, title: String, icon: ImageVector, items: List<ShelfItem>, always: Boolean = false): LibraryLink? {
        // One row per list, however many sources hold it: Mis favoritas comes both as the device's liked list and as one
        // of its offline playlists, and a list keyed twice is a crash the moment it scrolls into view.
        val ordered = items.distinctBy { it.browseId ?: it.title }
        if (ordered.isEmpty() && !always) return null
        val section = com.music.bitchord.data.model.HomeShelf(title, ordered)
        return action(id, title, icon) { onOpenShelf(section) }
    }
    val playlistsTitle = stringResource(R.string.playlists)
    val artistsTitle = stringResource(R.string.artists)
    val albumsTitle = stringResource(R.string.albums)
    val podcastsTitle = org.jetbrains.compose.resources.stringResource(Res.string.library_podcasts)
    val order by AppSettings.libraryLinkOrder.collectAsStateWithLifecycle()
    return listOfNotNull(
        link(stringResource(R.string.downloads), "local:downloads"),
        link(stringResource(R.string.local_music), "local:all"),
        link(stringResource(R.string.cached_songs), CACHE_FOLDER_BROWSE_ID),
        // Only once set up in Sources; unconfigured, they'd just be dead ends.
        link(stringResource(R.string.webdav), com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            .takeIf { webdavConfigured.isNotBlank() },
        link(stringResource(R.string.smb), com.music.bitchord.data.smb.SmbConfig.BROWSE_ID)
            .takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
        // Always there, whatever they hold: a section that is not drawn until it has something is one nobody knows to
        // fill. Empty, it says how to (see LibraryListPage).
        sectionRow("shelf:playlists", playlistsTitle, SfIcons.PlaylistPlay, deviceItems, always = true),
        sectionRow("shelf:artists", artistsTitle, SfIcons.Artist, artistItems, always = true),
        sectionRow("shelf:albums", albumsTitle, SfIcons.Album, albumItems, always = true),
        sectionRow("shelf:podcasts", podcastsTitle, SfIcons.Radio, podcastItems, always = true),
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
            // Drawn, not a picture: see FolderCover, which gives it the folders' look.
            thumbnailUrl = null,
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
    "local:downloads" -> SfIcons.Download
    "local:all" -> SfIcons.Folder
    CACHE_FOLDER_BROWSE_ID -> SfIcons.Storage
    com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID -> SfIcons.Cloud
    com.music.bitchord.data.smb.SmbConfig.BROWSE_ID -> SfIcons.Network
    else -> null
}

/** The filled counterpart of [deviceFolderIcon], for the folder's cover: the same symbol, solid. */
fun deviceFolderCoverGlyph(browseId: String?): ImageVector? = when (browseId) {
    "local:downloads" -> SfIcons.DownloadFill
    "local:all" -> SfIcons.FolderFill
    CACHE_FOLDER_BROWSE_ID -> SfIcons.StorageFill
    com.music.bitchord.data.OfflinePlaylists.PREFIX + com.music.bitchord.data.OfflinePlaylists.LIKED_ID -> SfIcons.StarFill
    com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID -> SfIcons.CloudFill
    com.music.bitchord.data.smb.SmbConfig.BROWSE_ID -> SfIcons.NetworkGlyph
    else -> null
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"

/** A podcast show's page id on YouTube Music; the followed shows share the artists' table and are told apart by it. */
private const val PODCAST_PREFIX = "MPSP"

/** The albums downloaded whole, as cards, for the Albums section. */
@Composable
fun libraryAlbumItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> =
    downloadedReleases.filterNot { it.playlist }.map { release ->
        ShelfItem(
            title = release.title,
            subtitle = release.subtitle,
            thumbnailUrl = release.thumbnailUrl,
            videoId = null,
            browseId = Downloads.pageIdFor(release.id),
        )
    }
