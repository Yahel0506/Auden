package com.music.bitchord.ui.components

import org.jetbrains.compose.resources.vectorResource
import com.music.bitchord.sharedui.resources.ytmusic_logo
import com.music.bitchord.sharedui.resources.Res
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.foundation.layout.size
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads

/**
 * The album or playlist a long-press is acting on.
 *
 * Deliberately not one of the browse models: every surface in the app describes
 * a release with a different type — a [com.music.bitchord.data.model.ShelfItem]
 * on the home feed, a [com.music.bitchord.data.model.BrowseItem] in search, a
 * [com.music.bitchord.data.model.DetailPage] on the page itself, a plain album
 * name on the Local Music tab — and the menu needs the same five things from
 * all of them.
 */
data class BrowseTarget(
    /**
     * What to fetch the track list with, or null when there is nothing to
     * fetch: a Local Music album is a grouping of rows already on screen, not
     * a page anything can be asked for.
     */
    val browseId: String?,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val type: BrowseType = BrowseType.OTHER,
    /**
     * The tracks already in hand. Empty for a card whose page was never
     * opened — the queue actions then fetch the listing themselves, which is
     * why they are offered either way.
     */
    val songs: List<Song> = emptyList(),
    /**
     * Set when this is one of the account's own playlists, which is the only
     * case where renaming and deleting are things that can be done to it.
     *
     * Filled in where the menu is raised rather than by whatever built the
     * target: only the playlist's own page states who made it, so for a card
     * this has to be sent for and arrives after the menu is already up. Left
     * null by every caller, and null is also the honest answer while the
     * question is still out.
     */
    val playlist: UserPlaylist? = null,
    /**
     * False when the menu was opened from the page it would otherwise navigate
     * to — the release page's own overflow. Play, Shuffle and Open are already
     * on that page, so there they are left off the menu.
     */
    val fromCard: Boolean = true,
    /**
     * The id this release is recorded under in [com.music.bitchord.download.Downloads.collections],
     * when it was downloaded whole — set so [onDelete] in the caller can offer
     * "Delete download" alongside (or instead of) deleting the account's own
     * copy.
     *
     * Left for the caller to resolve rather than derived here: a card off the
     * Local Music screen has no browse id to derive it from at all (see
     * `albumEntries` in `LocalMusicScreen`), while a real page's id needs
     * `Downloads.recordIdOf` run on it first for a downloaded playlist. Both are
     * questions about the download record, not about what this menu is.
     */
    val downloadId: String? = null,
)

/**
 * Long-press menu for an album or playlist — the collection-level counterpart
 * to [SongActionsMenu], and like it only the rows: the frame is the host's,
 * [IosMenuPopover] off a ⋯ or [HeldContextMenu] under a held card.
 *
 * Every action is optional, and which ones a caller passes is how the same
 * menu serves a card on the home feed and the page that card opens. A card
 * has no other way to play its album without navigating to it, so it gets Play
 * and Shuffle; the page's own header already carries both, so there they are
 * null and the menu is the queue rows and the download.
 *
 * The queue rows are always offered. They are the reason this menu exists: a
 * release is exactly the kind of thing someone wants *after* what is playing
 * rather than instead of it, and until now the only way to queue one was to
 * open it and long-press its tracks one at a time.
 *
 * Deleting asks a second time, in place. A playlist is the only thing in this
 * app whose loss can't be undone by tapping the same row again, and a
 * mis-tapped card in a shelf is exactly how it would happen.
 */
@Composable
fun BrowseActionsMenu(
    target: BrowseTarget,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    /** Null where the menu was opened from the page it would navigate to. */
    onOpen: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    /**
     * Shares the release's own YouTube Music link — the album/playlist
     * counterpart to a track's own Share tile in [SongActionsMenu]. Null for
     * anything with no such link to share: an artist card, or a page built
     * from something other than a real browse id (a Local Music grouping).
     */
    onShare: (() -> Unit)? = null,
    /**
     * Set whenever [target] is a playlist, regardless of who owns it — pinning
     * doesn't touch the account, only what sits at the top of this device's
     * Library tab. Null everywhere else (albums, artists), where "pin" has
     * nothing to mean.
     */
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    /**
     * Opens the dedicated editor for this list — its name, its cover, its description — in place of a row for
     * each: set for a playlist the account owns and for any kept on this device.
     */
    onEditPlaylist: (() -> Unit)? = null,
    /**
     * Opens the reorder page for one of the account's own playlists — set
     * under the same rule as [onRename], since YouTube refuses to rearrange a
     * playlist the account only saved.
     */
    onReorder: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * Removes the files this release was downloaded as, when it was downloaded
     * whole — see [BrowseTarget.downloadId]. Independent of [onDelete]: that one
     * deletes the playlist from the account, this one only ever touches what's
     * on the device, so both can be offered together for an owned playlist that
     * also happens to be downloaded.
     */
    onDeleteDownload: (() -> Unit)? = null,
    /**
     * Repairs the missing tags of the downloaded and device files in this list —
     * see [com.music.bitchord.download.MetadataRepair]. Offered on every playlist
     * and folder; a song that only streams is skipped.
     */
    onRepairMetadata: (() -> Unit)? = null,
    /**
     * A playlist made on this device: sends it to YouTube Music, and — once that
     * playlist exists — rewrites it to match this one again. Null for anything
     * that came from YouTube Music, which is the one that depends on it.
     */
    onSyncYtMusic: (() -> Unit)? = null,
    /** An imported playlist: brings it up to date from where it came from, by hand. */
    onRefreshFromOrigin: (() -> Unit)? = null,
    ytSync: YtSyncRowState = YtSyncRowState(),
    /** Playlists kept on this device: opens the picker of formats to write the playlist as. */
    onExport: (() -> Unit)? = null,
    /** Playlists kept on this device: makes a copy to change without touching the original. */
    onDuplicate: (() -> Unit)? = null,
    /** Looks up and keeps lyrics for every track of the release, in the background. */
    onFetchLyrics: (() -> Unit)? = null,
    /** A cloud playlist or album: copies it to (or drops it from) the device library. */
    onToggleDevice: (() -> Unit)? = null,
    savedOnDevice: Boolean = false,
    savingOnDevice: Boolean = false,
    /** Files every song of the release into playlists at once — the picker a single song opens, for all of them. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** The release's own page only: starts a multi-selection on its track list. */
    onSelectSongs: (() -> Unit)? = null,
) {
    var page by remember { mutableStateOf(MenuPage.MAIN) }

    // Each group is a handful of related rows. With more than one it is a door to a page of its own; with a single
    // row a door would only hide it, so that row stays where it is.
    val tags: @Composable ColumnScope.() -> Unit = {
        onFetchLyrics?.let { ActionRow(BitChordIcons.Lyrics, stringResource(R.string.find_lyrics_for_all), onClick = it) }
        onRepairMetadata?.let { ActionRow(SfIcons.Upgrade, stringResource(R.string.metadata_repair), onClick = it) }
    }
    val tagsCount = listOf(onFetchLyrics, onRepairMetadata).count { it != null }
    val manage: @Composable ColumnScope.() -> Unit = {
        onReorder?.let { ActionRow(SfIcons.Reorder, stringResource(R.string.reorder_songs), onClick = it) }
        onDuplicate?.let { ActionRow(Icons.Rounded.ContentCopy, stringResource(R.string.duplicate_playlist), onClick = it) }
        onExport?.let { ActionRow(Icons.Rounded.FileUpload, stringResource(R.string.export_playlist), onClick = it) }
        onRefreshFromOrigin?.let { ActionRow(Icons.Rounded.Sync, stringResource(R.string.refresh_from_origin), onClick = it) }
        onSyncYtMusic?.let { sync -> YtSyncRow(ytSync, sync) }
        onShare?.let { ActionRow(SfIcons.Share, stringResource(R.string.share), onClick = it) }
    }
    val manageCount = listOf(onReorder, onDuplicate, onExport, onRefreshFromOrigin, onSyncYtMusic, onShare).count { it != null }
    val filing = onToggleDevice != null || onDownloadAll != null || onDeleteDownload != null

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        MenuPages(page, { it.ordinal.coerceAtMost(1) }, Modifier.fillMaxWidth()) { current ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (current) {
                    MenuPage.MAIN -> {
                        // What is done with the release itself, in the order it is wanted: play, queue, file, change.
                        onPlay?.let { ActionRow(SfIcons.Play, stringResource(R.string.play), onClick = it) }
                        onShuffle?.let { ActionRow(SfIcons.Shuffle, stringResource(R.string.shuffle), onClick = it) }
                        ActionRow(SfIcons.PlayNext, stringResource(R.string.play_next), onClick = onPlayNext)
                        ActionRow(SfIcons.PlayLast, stringResource(R.string.add_to_queue), onClick = onAddToQueue)
                        if (onAddToPlaylist != null || onSelectSongs != null) IosMenuSeparator()
                        onAddToPlaylist?.let { ActionRow(SfIcons.AddToPlaylist, stringResource(R.string.add_to_playlist), onClick = it) }
                        onSelectSongs?.let { ActionRow(Icons.Rounded.Checklist, stringResource(R.string.select_songs), onClick = it) }
                        onOpen?.let { ActionRow(SfIcons.Open, target.type.localizedOpenLabel(), onClick = it) }
                        // Keeping it: Download is a button of its own, and once there is a download, so is deleting it.
                        if (filing) IosMenuSeparator()
                        onToggleDevice?.let {
                            ActionRow(
                                icon = when {
                                    savingOnDevice -> BitChordIcons.Clock
                                    savedOnDevice -> BitChordIcons.Check
                                    else -> Icons.Rounded.PhoneIphone
                                },
                                label = stringResource(if (savedOnDevice) R.string.remove_from_device else R.string.save_to_device),
                                value = if (savingOnDevice) stringResource(R.string.saving_ellipsis) else null,
                                onClick = it,
                            )
                        }
                        onDownloadAll?.let { DownloadAllRow(target, it) }
                        if (onDeleteDownload != null) {
                            ActionRow(SfIcons.Trash, stringResource(R.string.delete_download), tint = MaterialTheme.colorScheme.error, onClick = onDeleteDownload)
                        }
                        // Everything else sits behind a few named doors, each with only its own options.
                        if (onEditPlaylist != null || onTogglePin != null || tagsCount + manageCount > 0) IosMenuSeparator()
                        onEditPlaylist?.let { ActionRow(SfIcons.Pencil, stringResource(R.string.edit_playlist), onClick = it) }
                        // Pinning is a row of the menu, not something inside "Manage".
                        onTogglePin?.let { ActionRow(SfIcons.Pin, stringResource(if (isPinned) R.string.unpin else R.string.pin), onClick = it) }
                        if (tagsCount > 1) {
                            ActionRow(BitChordIcons.Lyrics, stringResource(R.string.browse_group_tags), chevron = true) { page = MenuPage.TAGS }
                        } else {
                            tags()
                        }
                        if (manageCount > 1) {
                            ActionRow(Icons.Rounded.MoreHoriz, stringResource(R.string.browse_group_more), chevron = true) { page = MenuPage.MORE }
                        } else {
                            manage()
                        }
                        if (onDelete != null) {
                            IosMenuSeparator()
                            ActionRow(SfIcons.Trash, stringResource(R.string.delete_playlist), tint = MaterialTheme.colorScheme.error, onClick = onDelete)
                        }
                    }
                    MenuPage.TAGS -> {
                        MenuPageHeader(stringResource(R.string.browse_group_tags)) { page = MenuPage.MAIN }
                        tags()
                    }
                    MenuPage.MORE -> {
                        MenuPageHeader(stringResource(R.string.browse_group_more)) { page = MenuPage.MAIN }
                        manage()
                    }
                }
            }
        }
    }
}

private enum class MenuPage { MAIN, TAGS, MORE }

/**
 * Saying which of the three it is, rather than offering the same row whatever the state: a release is asked for
 * here, and a menu that can't say "already on the device" leaves the question open. The tap is left live in every
 * state: [Downloads.enqueue] leaves a track that is saved, queued or running alone.
 */
@Composable
private fun DownloadAllRow(target: BrowseTarget, download: () -> Unit) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val requested by Downloads.requested.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    // Only the tracks *this release* asked for — two releases can share a track, and reading the whole queue would
    // show this release waiting on a download some other one started. A failure is not a wait.
    val waiting = target.browseId?.let { requested[it] }.orEmpty().any { id ->
        when (active[id]) {
            is DownloadState.Queued, is DownloadState.Running -> true
            else -> false
        }
    }
    val ids = remember(target.songs) { target.songs.mapTo(HashSet()) { it.videoId } }
    val downloaded = !waiting && ids.isNotEmpty() &&
        target.songs.all { it.videoId in saved || dev.auden.library.links.LocalLinks.has(it) }
    ActionRow(
        icon = when {
            waiting -> SfIcons.Clock
            downloaded -> SfIcons.Downloaded
            else -> SfIcons.Download
        },
        label = stringResource(R.string.download_all),
        value = when {
            waiting -> stringResource(R.string.downloading)
            downloaded -> stringResource(R.string.downloaded)
            else -> null
        },
        onClick = download,
    )
}

@Composable
private fun BrowseType.localizedOpenLabel(): String = when (this) {
    BrowseType.ALBUM -> stringResource(R.string.open_album)
    BrowseType.ARTIST -> stringResource(R.string.open_artist)
    BrowseType.PLAYLIST -> stringResource(R.string.open_playlist)
    BrowseType.OTHER -> stringResource(R.string.open)
}

/** What the YouTube Music sync row shows: not started, a percentage, or an associated playlist to retry. */
data class YtSyncRowState(
    val percent: Int? = null,
    val associated: Boolean = false,
    val error: String? = null,
    val skipped: Int = 0,
)

@Composable
private fun YtSyncRow(state: YtSyncRowState, onClick: () -> Unit) {
    val running = state.percent != null
    val description = when {
        running -> null
        state.error != null -> state.error
        state.skipped > 0 -> stringResource(R.string.yt_sync_skipped, state.skipped)
        else -> null
    }
    // The YouTube Music mark until the playlist is linked, then the sync arrows; the label
    // stays the same, so a linked playlist does not read as one that failed.
    ActionRow(
        icon = if (state.associated) Icons.Rounded.Sync else vectorResource(Res.drawable.ytmusic_logo),
        label = if (running) stringResource(R.string.yt_sync_running) else stringResource(R.string.yt_sync),
        value = state.percent?.let { "$it%" },
        description = description,
        enabled = !running,
        onClick = onClick,
        leading = if (running) {
            {
                CircularProgressIndicator(
                    progress = { (state.percent ?: 0) / 100f },
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            null
        },
    )
}
