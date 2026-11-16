package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.SfIcons

/**
 * The app's one multi-selection of songs.
 *
 * One for the whole app rather than one per page: only the list on screen can
 * be picked from, and what the selection is *for* — its menu, the playlist
 * picker — is drawn by the host, over every page alike. Leaving the page puts
 * it away (see the host).
 *
 * Songs are kept whole, not as ids, in the order they were picked: the menu
 * acts on them after the list they came from may have reloaded or scrolled
 * away. [ordered] puts them back in the list's order when it is still there.
 *
 * Albums are selected by key as well as by their tracks: different albums can
 * share tracks, so inferring an album's selection from track ids made one held
 * playlist appear to select unrelated albums.
 */
@Stable
class SongSelectionState : SongSelection {
    override var selecting by mutableStateOf(false)
        private set
    private var picked by mutableStateOf<Map<String, Song>>(emptyMap())
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    /** What the list on screen is showing — see [SongSelection.offer]. */
    var visible by mutableStateOf<List<Song>>(emptyList())
        private set

    val count: Int get() = picked.size

    override fun isSelected(song: Song): Boolean = song.selectionKey in picked

    /** Opens the selection, on [song] when it was started from one. */
    fun start(song: Song? = null) {
        selecting = true
        if (song != null) picked = picked + (song.selectionKey to song)
    }

    override fun toggle(song: Song) {
        selecting = true
        val key = song.selectionKey
        picked = if (key in picked) picked - key else picked + (key to song)
    }

    fun isSelected(group: LocalGroup): Boolean = group.browseId in selectedGroups

    fun toggle(group: LocalGroup) {
        selecting = true
        if (group.browseId in selectedGroups) {
            selectedGroups = selectedGroups - group.browseId
            picked = picked - group.songs.mapTo(HashSet()) { it.selectionKey }
        } else {
            selectedGroups = selectedGroups + group.browseId
            picked = picked + group.songs.associateBy { it.selectionKey }
        }
    }

    override fun offer(visible: List<Song>) {
        this.visible = visible
    }

    val allVisibleSelected: Boolean
        get() = visible.isNotEmpty() && visible.all { it.selectionKey in picked }

    fun selectAllVisible() {
        selecting = true
        picked = picked + visible.associateBy { it.selectionKey }
    }

    /** Unticks everything and stays open, for another pick. */
    fun selectNone() {
        picked = emptyMap()
        selectedGroups = emptySet()
    }

    /** The picked songs, in the order of the list on screen; any no longer on it follow, as picked. */
    fun ordered(): List<Song> {
        val shown = visible.filter { it.selectionKey in picked }
        val shownKeys = shown.mapTo(HashSet()) { it.selectionKey }
        return shown + picked.values.filter { it.selectionKey !in shownKeys }
    }

    fun clear() {
        selecting = false
        picked = emptyMap()
        selectedGroups = emptySet()
        visible = emptyList()
    }
}

/**
 * The selection's own bar, floated under the top bar while one is being made —
 * over the list rather than in it, so it stays in reach however far down the
 * picks were made.
 *
 * Close on the left, the count, then Select all and the ⋮ that opens
 * [SelectionActionsMenu]: the same menu a single song's ⋮ opens, acting on
 * every picked song.
 */
@Composable
fun SongSelectionBar(
    state: SongSelectionState,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = state.count
    val shape = RoundedCornerShape(17.dp)
    val foreground = if (isSystemInDarkTheme()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(10.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::clear) {
            Icon(
                SfIcons.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = if (count == 0) {
                stringResource(R.string.select_songs)
            } else {
                pluralStringResource(R.plurals.songs_selected_count, count, count)
            },
            style = MaterialTheme.typography.titleSmall,
            color = foreground,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Only where the list said what "all" is — the search's mixed results don't.
        if (state.visible.isNotEmpty()) {
            val all = state.allVisibleSelected
            IconButton(onClick = { if (all) state.selectNone() else state.selectAllVisible() }) {
                Icon(
                    if (all) Icons.Rounded.RemoveDone else Icons.Rounded.Checklist,
                    contentDescription = stringResource(if (all) R.string.deselect_all else R.string.select_all),
                    tint = foreground,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        IconButton(onClick = onMore, enabled = count > 0, modifier = Modifier.menuAnchor()) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.more),
                tint = if (count > 0) MaterialTheme.colorScheme.primary else foreground.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * A single song's menu, for every picked song at once: only the rows, the frame
 * is the host's [IosMenuPopover], hung off the selection bar's ⋮.
 *
 * It keeps the rows that mean something of several songs — rate, file into
 * playlists, queue, download — and drops the ones that are about one: its
 * radio, its album, its artist, its info. A null action hides its row, which is
 * how the host says what the page the selection was made on can do.
 */
@Composable
fun SelectionActionsMenu(
    count: Int,
    /** Every picked song is liked already, so the tile takes the like back. */
    allLiked: Boolean,
    onToggleLike: (() -> Unit)?,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onUploadToWebDav: (() -> Unit)? = null,
    onRepairMetadata: (() -> Unit)? = null,
    onRemoveFromCache: (() -> Unit)? = null,
    onRemoveFromHistory: (() -> Unit)? = null,
    /** Deletes the picked downloads' files. */
    onDeleteDownloads: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // Says what the rows below are about to act on, where a song's menu shows the song.
            IosMenuRow(
                label = pluralStringResource(R.plurals.songs_selected_count, count, count),
                icon = SfIcons.Check,
                onClick = null,
            )
            IosMenuSeparator()
            onToggleLike?.let {
                IosMenuQuickActions {
                    IosMenuQuickAction(
                        icon = if (allLiked) SfIcons.StarFill else SfIcons.Star,
                        label = stringResource(R.string.like),
                        tint = if (allLiked) accent else null,
                        onClick = it,
                    )
                }
                IosMenuSeparator()
            }
            ActionRow(
                icon = SfIcons.AddToPlaylist,
                label = stringResource(R.string.add_to_playlist),
                accent = accent,
                onClick = onAddToPlaylist,
            )
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = SfIcons.RemoveFromPlaylist,
                    label = stringResource(R.string.remove_from_playlist),
                    accent = accent,
                    onClick = it,
                )
            }
            IosMenuSeparator()
            ActionRow(SfIcons.PlayNext, stringResource(R.string.play_next), accent = accent, onClick = onPlayNext)
            ActionRow(SfIcons.PlayLast, stringResource(R.string.add_to_queue), accent = accent, onClick = onAddToQueue)
            onDownload?.let {
                ActionRow(SfIcons.Download, stringResource(R.string.download), accent = accent, onClick = it)
            }
            onUploadToWebDav?.let {
                ActionRow(SfIcons.Upload, stringResource(R.string.upload_to_webdav), accent = accent, onClick = it)
            }
            onRepairMetadata?.let {
                ActionRow(SfIcons.Upgrade, stringResource(R.string.metadata_repair), accent = accent, onClick = it)
            }
            onRemoveFromHistory?.let {
                ActionRow(SfIcons.History, stringResource(R.string.remove_from_history), accent = accent, onClick = it)
            }
            if (onRemoveFromCache != null || onDeleteDownloads != null) {
                IosMenuSeparator()
                onRemoveFromCache?.let {
                    ActionRow(
                        icon = SfIcons.Trash,
                        label = stringResource(R.string.remove_from_cache),
                        tint = MaterialTheme.colorScheme.error,
                        accent = accent,
                        onClick = it,
                    )
                }
                onDeleteDownloads?.let {
                    ActionRow(
                        icon = SfIcons.Trash,
                        label = stringResource(R.string.delete_download),
                        tint = MaterialTheme.colorScheme.error,
                        accent = accent,
                        onClick = it,
                    )
                }
            }
        }
    }
}

/**
 * The selection itself: one for the app, since there is one screen to pick from. Provided to every
 * [SongRow] as [LocalSongSelection] at the root, and read by the host that draws its bar and menu.
 */
val AppSongSelection = SongSelectionState()

/** A question put before songs leave playlists or files leave the device; [onCancel] is what a "no" still does. */
class SelectionConfirm(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onCancel: () -> Unit = {},
    val onConfirm: () -> Unit,
)
