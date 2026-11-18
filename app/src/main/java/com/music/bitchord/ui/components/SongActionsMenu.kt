package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import com.music.bitchord.ui.icons.BrokenHeart
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import com.music.bitchord.ui.components.trackArtSize
import androidx.compose.ui.layout.ContentScale
import com.music.bitchord.R

import android.net.Uri
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.bitchord.ui.player.ThinSlider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.webdav.WebDavUploads
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.theme.ArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Long-press menu for a track, in the shape music apps normally use.
 *
 * The account actions lead — rating, playlists, library — because they are
 * what the menu is opened for; the queue and navigation rows below it were
 * always the fallback for "I meant to do something with this song".
 *
 * Everything that writes to the account is hidden outright when [signedIn] is
 * false rather than shown and refused. A track playing from a local file or a
 * finished download (`song.localUri != null`) carries no YouTube identity to
 * rate, fetch again or share, so it gets the offline player's rows instead:
 * Like and Add to playlist against the device's own playlists (see
 * [com.music.bitchord.data.OfflinePlaylists]), signed in or not.
 *
 * [showSleepTimer] and [onShare] are the player's extras: a sleep timer isn't a
 * property of some row in a list, so it only appears where it means something.
 *
 * [onDownload] is only the *start* of a download — cancelling one and deleting
 * a saved file are answered here, because neither needs anything the caller
 * has. Starting one might: below API 29 it needs a storage permission that only
 * an Activity can ask for.
 *
 * Only the rows, in the iOS panel's own colours: the frame is the host's —
 * [IosMenuPopover] hung off the ⋮ or the player's ⋯ that opened it, or
 * [HeldContextMenu] under the row a hold lifted.
 */
@Composable
fun SongActionsMenu(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onDownload: () -> Unit,
    /** Repairs this song's file's missing tags; offered only for a song that is a file. */
    onRepairMetadata: (() -> Unit)? = null,
    /**
     * Copies this track to the WebDAV server. Null hides the row — the caller
     * passes one only for a readable device file while a server is
     * configured. Uploading while another upload of the same track runs just
     * re-runs it; the conflict dialog keeps that idempotent.
     */
    onUploadToWebDav: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /**
     * Removes this track from the account's listening history. Null hides the
     * row — the History screen passes one only when the menu is opened there.
     */
    onRemoveFromHistory: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    /**
     * Sends the playing track back to YouTube's own upload, and keeps it there.
     * Offered for any player item that has one behind it — a substituted copy
     * can be the wrong recording from the first second, not only after a
     * visible upgrade. See
     * [com.music.bitchord.playback.hasYouTubeOriginal].
     */
    onRollbackToOriginal: (() -> Unit)? = null,
    /**
     * The way back from a revert: available only for a player item the listener
     * has pinned to YouTube's own upload, where nothing will go looking for a
     * better copy again until they ask.
     * See [com.music.bitchord.playback.OriginalVersion].
     */
    onUpgradeQuality: (() -> Unit)? = null,
    /** Keeps the upgrade row visible but untappable while its lookup is running. */
    upgradeQualityInProgress: Boolean = false,
    /**
     * Switches the playing track between its video and audio-only cuts. Null
     * hides the row — offered for the playing track only. See
     * [com.music.bitchord.playback.PlaybackService.smoothSwapCurrentTrackVersion].
     */
    onToggleAudioVersion: (() -> Unit)? = null,
    /** Which cut is playing now, so the row can offer the other one. */
    isAudioVersion: Boolean = false,
    /**
     * A switch already looked for the other cut and found none: the row says
     * so, greyed out, rather than offering the same dead end again.
     */
    audioVersionMissing: Boolean = false,
    onShare: (() -> Unit)? = null,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /** Opens the timing control offered only by the main player's menu. */
    onLyricsOffset: (() -> Unit)? = null,
    /**
     * Opens the Automix mix popup on the next transition. Null hides the row —
     * the player passes one only while Automix is on.
     */
    onAutomixOverview: (() -> Unit)? = null,
    /**
     * True while a lookup for this track's album/artist ids is still in
     * flight, so it isn't yet known whether "Open album" and "Open artist"
     * belong on this menu at all. Only the player ever opens a menu before
     * it knows; everywhere else this is simply false, and the two rows behave
     * as before — present when the id is there, absent when it never was.
     */
    resolvingLinks: Boolean = false,
    /** The file the player is reading for this song right now, to outline it among its copies. */
    playingUri: String? = null,
    /** A row from a Spotify playlist: lets its YouTube Music match be corrected by hand. */
    onFixSpotifyMatch: (() -> Unit)? = null,
    /**
     * Starts a multi-selection on this song's list, with this song picked. Null
     * hides the row — the player's menu, whose song is on no list.
     */
    onSelect: (() -> Unit)? = null,
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    var otherOpen by remember(song.videoId) { mutableStateOf(false) }
    // Managing the song's downloaded files is a page of this menu, in whatever panel the menu is in.
    var managing by remember(song.videoId) { mutableStateOf(false) }
    // A menu has no pages to swap, so Song Info opens as a dialog.
    // Neutral rather than the sleeve's colours: the panel floats over the page
    // or the player, and an accent pitched against the artwork's tint is not
    // guaranteed to read against the panel.
    val palette = neutralMenuPalette()
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    // A local file or a finished download is played off the device, with no account
    // behind it to rate, save, queue into a playlist or fetch again. A download
    // still keeps the YouTube id it came from, so it can share its link; only a
    // device-only file has no link to share.
    val isOffline = song.localUri != null

    val rows: @Composable ColumnScope.() -> Unit = {
        // Groups are drawn as they turn out to have something in them, and the
        // separator goes *before* a group, never after — so a menu whose last
        // group is empty doesn't end on a stray line.
        var drawnAny = false
        fun startsGroup(): Boolean = drawnAny.also { drawnAny = true }
        // Every song can be hearted: on the device without an account, and on the account too once one is signed in.
        val canRate = true
        // A finished download keeps the YouTube id it was downloaded from, so its link still
        // shares; only a device-only file has no link to share. MainActivity already hides
        // Share when there is no video id at all — this is the same guard, at the menu.
        val shareAction = onShare?.takeIf { song.videoId.isNotBlank() }

        // Leads with the actions it is opened for most, as tiles: the rating
        // — kept up after a tap, so the new state shows in place — and Share.
        if (canRate || shareAction != null) {
            if (startsGroup()) IosMenuSeparator()
            IosMenuQuickActions {
                if (canRate) {
                    IosMenuQuickAction(
                        icon = if (liked) SfIcons.StarFill else SfIcons.Star,
                        label = stringResource(R.string.like),
                        tint = if (liked) palette.accent else null,
                        onClick = onToggleLike,
                    )
                }
                // No dislike for a device file: there is no recommendation
                // feed on the device for one to steer.
                if (signedIn && !isOffline) {
                    IosMenuQuickAction(
                        icon = if (disliked) BrokenHeart.Fill else BrokenHeart.Outline,
                        label = stringResource(R.string.dislike),
                        tint = if (disliked) palette.accent else null,
                        onClick = onToggleDislike,
                    )
                }
                shareAction?.let {
                    IosMenuQuickAction(icon = SfIcons.Share, label = stringResource(R.string.share), onClick = it)
                }
            }
        }

        // Leads the list whenever it's available: which recording is playing
        // is the one question that has to be answered before any of the rows
        // below mean anything — there is no point rating, queueing or
        // downloading the wrong version of a song.
        //
        // The two are never both present — one is offered for a track playing
        // a substituted copy, the other for a track held on YouTube's own —
        // and between them they are the whole of the choice, which is why they
        // sit in the same group.
        if (onRollbackToOriginal != null || onUpgradeQuality != null || onToggleAudioVersion != null) {
            if (startsGroup()) IosMenuSeparator()
            (onRollbackToOriginal ?: onUpgradeQuality)?.let {
                ActionRow(
                    icon = if (onRollbackToOriginal != null) SfIcons.Revert else SfIcons.Upgrade,
                    label = stringResource(
                        if (onRollbackToOriginal != null) {
                            R.string.revert_to_original
                        } else {
                            R.string.upgrade_quality
                        },
                    ),
                    accent = palette.accent,
                    enabled = onRollbackToOriginal != null || !upgradeQualityInProgress,
                    onClick = it,
                )
            }
            // Which recording, not which quality — a different question from
            // the row above, so it sits right under it rather than merged
            // into it.
            onToggleAudioVersion?.let {
                ActionRow(
                    icon = if (isAudioVersion) SfIcons.Video else SfIcons.MusicNote,
                    label = stringResource(
                        when {
                            audioVersionMissing && isAudioVersion -> R.string.no_video_version
                            audioVersionMissing -> R.string.no_audio_version
                            isAudioVersion -> R.string.convert_to_video
                            else -> R.string.convert_to_audio
                        },
                    ),
                    accent = palette.accent,
                    enabled = !audioVersionMissing,
                    onClick = it,
                )
            }
        }

        // The account's rows, or for a local file or download the offline
        // player's own pair: a heart that files the track under this device's
        // Liked playlist, and the device's playlists. [likeStatus] is that
        // playlist's answer there, not YouTube's, and neither needs an account.
        // Playlists can live on the device, so these rows never wait for an
        // account.
        run {
            if (startsGroup()) IosMenuSeparator()
            ActionRow(
                icon = SfIcons.AddToPlaylist,
                label = stringResource(R.string.add_to_playlist),
                accent = palette.accent,
                onClick = onAddToPlaylist,
            )
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = SfIcons.RemoveFromPlaylist,
                    label = stringResource(R.string.remove_from_playlist),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            // With the playlist rows: filing several songs at once is what a
            // selection is started for more often than anything else.
            onSelect?.let {
                ActionRow(
                    icon = Icons.Rounded.Checklist,
                    label = stringResource(R.string.select_songs),
                    accent = palette.accent,
                    onClick = it,
                )
            }
        }

        // Where the track goes next, and what is kept of it. The file rows
        // ride in this group rather than one of their own because for a
        // device file they can all be absent, and a group may not be empty.
        if (startsGroup()) IosMenuSeparator()
        ActionRow(
            icon = SfIcons.Radio,
            label = stringResource(R.string.start_radio),
            accent = palette.accent,
            onClick = onStartRadio,
        )
        ActionRow(
            icon = SfIcons.PlayNext,
            label = stringResource(R.string.play_next),
            accent = palette.accent,
            onClick = onPlayNext,
        )
        ActionRow(
            icon = SfIcons.PlayLast,
            label = stringResource(R.string.add_to_queue),
            accent = palette.accent,
            onClick = onAddToQueue,
        )
        DownloadRow(song, palette, isOffline, onDownload, playingUri) { managing = true }
        onRepairMetadata?.let {
            ActionRow(
                icon = SfIcons.Upgrade,
                label = stringResource(R.string.metadata_repair),
                accent = palette.accent,
                onClick = it,
            )
        }
        WebDavUploadRow(song, palette, isOffline, onUploadToWebDav)
        onFixSpotifyMatch?.let {
            ActionRow(
                icon = Icons.Rounded.SwapHoriz,
                label = stringResource(R.string.spotify_fix_match),
                accent = palette.accent,
                onClick = it,
            )
        }
        onRemoveFromHistory?.let {
            ActionRow(
                icon = SfIcons.History,
                label = stringResource(R.string.remove_from_history),
                accent = palette.accent,
                onClick = it,
            )
        }

        // The way to the song's artist and its album, and what is known of it, in a group of their own, the
        // information last.
        run {
            if (startsGroup()) IosMenuSeparator()
            song.artistId?.let { id ->
                ActionRow(
                    icon = SfIcons.Artist,
                    label = stringResource(R.string.open_artist),
                    accent = palette.accent,
                    onClick = { onOpenArtist(id) },
                )
            }
            song.albumId?.let { id ->
                ActionRow(
                    icon = SfIcons.Album,
                    label = stringResource(R.string.open_album),
                    accent = palette.accent,
                    onClick = { onOpenAlbum(id) },
                )
            }
            ActionRow(
                icon = SfIcons.Info,
                label = stringResource(R.string.song_info),
                accent = palette.accent,
                onClick = { SongInfoRequest.show(song) },
            )
        }

        // The player's own, kept in a menu of their own: the sleep timer, the Automix summary, the lyrics' timing and
        // the log, about the track playing right now rather than the song as a thing in a library.
        if (showSleepTimer || onLyricsOffset != null || onAutomixOverview != null || onCopyLog != null) {
            if (startsGroup()) IosMenuSeparator()
            ActionRow(
                icon = SfIcons.Sliders,
                label = stringResource(R.string.other_functions),
                accent = palette.accent,
            ) { otherOpen = true }
        }
    }

    // The page the "other functions" open: each of them as it was a row of the menu.
    val otherRows: @Composable ColumnScope.() -> Unit = {
        MenuPageHeader(stringResource(R.string.other_functions)) { otherOpen = false }
        if (showSleepTimer) {
            ActionRow(
                icon = SfIcons.SleepTimer,
                label = stringResource(R.string.sleep_timer),
                value = sleepTimerStatus(),
                accent = palette.accent,
            ) { pickingSleepTimer = true }
        }
        // The same popup "Mixing" opens mid-blend, on the transition still to come.
        onAutomixOverview?.let {
            ActionRow(
                icon = SfIcons.GraphicEq,
                label = stringResource(R.string.automix_overview),
                accent = palette.accent,
                onClick = it,
            )
        }
        // Local files and completed downloads use the same player lyric clock (including embedded synced lyrics),
        // so they need this control just as much as streamed songs do.
        onLyricsOffset?.let {
            ActionRow(
                icon = SfIcons.Sliders,
                label = stringResource(R.string.lyrics_offset),
                accent = palette.accent,
                onClick = it,
            )
        }
        // Last: the one row here nobody reaches for by accident.
        onCopyLog?.let {
            ActionRow(SfIcons.Bug, stringResource(R.string.copy_log), accent = palette.accent, onClick = it)
        }
    }

    // The system's back takes a page of this menu back one step — the sleep timer to the other functions, those and
    // the download manager to the rows — and only from the rows does it close the menu. Not a plain BackHandler: inside
    // the player's sheet that loses to the sheet's own dismiss (see the menu panel's own).
    com.music.bitchord.ui.player.PlayerBackHandler(enabled = pickingSleepTimer || managing || otherOpen) {
        when {
            pickingSleepTimer -> pickingSleepTimer = false
            managing -> managing = false
            else -> otherOpen = false
        }
    }

    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        // The sleep timer and the download manager are pages of this menu: each opens over the rows, in the same
        // panel and the same place, and gives them back.
        // Pages, deeper the further in: the rows, the other functions, the sleep timer opened from them, the download manager.
        MenuPages(if (pickingSleepTimer) 2 else if (managing) 3 else if (otherOpen) 1 else 0, { it }, Modifier.fillMaxWidth()) { current ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (current) {
                    1 -> otherRows()
                    2 -> SleepTimerPicker(palette = palette, onBack = { pickingSleepTimer = false })
                    3 -> ManageDownloadPage(song, playingUri, palette) { managing = false }
                    else -> rows()
                }
            }
        }
    }
}

/**
 * Row density and layout, set by whichever surface the rows are drawn on: the
 * playlist picker's sheet, or a menu panel.
 */
internal enum class ActionRowStyle { Sheet, Menu }


internal val LocalActionRowStyle = staticCompositionLocalOf { ActionRowStyle.Sheet }

/**
 * The popup's colours: the iOS menu panel's own, so the menu reads the same in
 * light and dark whatever the artwork is. Shaped as an [ArtworkPalette] so every
 * row below takes it exactly as it takes a sleeve's.
 */
@Composable
private fun neutralMenuPalette(): ArtworkPalette {
    val accent = MaterialTheme.colorScheme.primary
    val colors = iosMenuColors()
    return remember(accent, colors) {
        ArtworkPalette(
            background = colors.fill,
            wash = colors.fill,
            elevated = colors.fill,
            accent = accent,
            onBackground = colors.label,
            onBackgroundVariant = colors.secondaryLabel,
            divider = colors.separator,
        )
    }
}

/**
 * One row carrying the whole life of a download: start it, watch it, cancel it,
 * and delete what it produced.
 *
 * A row rather than a screen because that is the size of the decision. The
 * files land in the device's own Music folder, which already has a manager
 * — the Files app — and building a second one inside this app would be
 * duplicating it in a worse place. What this app uniquely knows is which *song*
 * a file belongs to, and that is exactly what this row says.
 *
 * The state comes straight from [Downloads] rather than through the caller: it
 * changes while the menu is open, and threading a flow through the menu's
 * signature would buy nothing over reading it where it's drawn — the same
 * arrangement the sleep timer row already uses.
 */
@Composable
private fun DownloadRow(song: Song, palette: ArtworkPalette, isOffline: Boolean, onDownload: () -> Unit, playingUri: String?, onManage: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    // The record is a claim about a folder the user manages themselves, so it
    // is checked against the disk rather than trusted — re-checked whenever the
    // record for this track changes, which is what makes the row settle onto
    // "Saved" the moment a download finishes.
    //
    // Seeded with the record rather than with null so that moment isn't a
    // flicker: a finished download clears the running state and writes the
    // record in the same breath, and starting pessimistic would show "Download"
    // again for as long as the check off the main thread takes.
    val recorded = saved[song.videoId]
    val revision by dev.auden.library.links.LocalLinks.revision.collectAsStateWithLifecycle()
    val copies = remember(song.videoId, recorded, revision) { dev.auden.library.links.LocalLinks.copiesOf(song) }
    val file by produceState(recorded?.let(Uri::parse), song.videoId, recorded) {
        value = Downloads.savedUri(context, song.videoId)
    }

    // A failure is worth stating once. Leaving it set would have the row still
    // reporting last week's dead connection the next time the menu is opened.
    DisposableEffect(song.videoId) {
        onDispose { Downloads.dismissFailure(song.videoId) }
    }

    when (val state = active[song.videoId]) {
        is DownloadState.Queued -> ActionRow(
            icon = SfIcons.Download,
            label = stringResource(R.string.queued),
            value = stringResource(R.string.cancel),
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Running -> ActionRow(
            icon = SfIcons.Download,
            label = stringResource(R.string.download_notification_title),
            // Indeterminate until the first response names a length; a
            // stuck "0%" reads as broken where a bare label reads as starting.
            value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
            tint = palette.accent,
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Failed -> ActionRow(
            icon = SfIcons.ErrorOutline,
            label = state.reason,
            value = stringResource(R.string.retry),
            // Not the artwork's colour: a failure has to stay legible as a
            // failure whatever the sleeve happens to be tinted.
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onDownload,
        )

        null -> if (copies.isNotEmpty() || file != null) {
            // The app's own download, and any other file that is this same song.
            // What is done with the files is an alert of its own, not a drawer in the menu: one row leads to it.
            ActionRow(
                icon = Icons.Rounded.DownloadDone,
                label = stringResource(R.string.manage_download),
                value = if (copies.size > 1) stringResource(R.string.download_copies_count, copies.size) else null,
                tint = palette.accent,
                accent = palette.accent,
                chevron = true,
            ) { onManage?.invoke() }
            // Only the app's own download has lyrics to fetch into; a file found on the device is left as it is.
            if (file != null && !isOffline && !Downloads.hasLyrics(song.videoId)) {
                val lyricsActive by Downloads.lyricsActive.collectAsStateWithLifecycle()
                val isDownloadingLyrics = song.videoId in lyricsActive
                ActionRow(
                    icon = BitChordIcons.Lyrics,
                    label = if (isDownloadingLyrics) "Downloading lyrics..." else "Download lyrics",
                    accent = palette.accent,
                    onClick = {
                        if (!isDownloadingLyrics) {
                            Downloads.enqueueLyrics(context, song)
                        }
                    },
                )
            }
        } else if (!isOffline) {
            ActionRow(
                icon = SfIcons.Download,
                label = stringResource(R.string.download),
                accent = palette.accent,
                onClick = onDownload,
            )
        }
    }
}

/**
 * Sends a device file to the WebDAV server. Sits beside the download row
 * because it is the same idea in the other direction — and like that row it
 * reads its state where it is drawn rather than threading it through the
 * menu's signature.
 *
 * Shown only for readable local files. A track already on the server, or a
 * stream with no bytes on this device, has nothing to send up.
 */
@Composable
private fun WebDavUploadRow(
    song: Song,
    palette: ArtworkPalette,
    isOffline: Boolean,
    onUpload: (() -> Unit)?,
) {
    onUpload ?: return
    if (!isOffline || !WebDavUploads.isUploadable(song)) return
    val active by WebDavUploads.active.collectAsStateWithLifecycle()
    when (val state = active[song.videoId]) {
        is WebDavUploads.TrackState.Running -> ActionRow(
            icon = SfIcons.Upload,
            label = stringResource(R.string.upload_to_webdav),
            value = "${(state.fraction * 100).toInt()}%",
            tint = palette.accent,
            accent = palette.accent,
            // In flight already; there is no cancel, so the row only reports.
            onClick = {},
        )
        is WebDavUploads.TrackState.Failed -> ActionRow(
            icon = SfIcons.Upload,
            label = state.reason,
            value = stringResource(R.string.try_again),
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onUpload,
        )
        else -> ActionRow(
            icon = SfIcons.Upload,
            label = stringResource(R.string.upload_to_webdav),
            accent = palette.accent,
            onClick = onUpload,
        )
    }
}

/** End of track or a duration, plus a way out once one is running. */
@Composable
private fun SleepTimerPicker(palette: ArtworkPalette, onBack: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()
    // A running timer no preset matches is a custom one: the row ticks and names it.
    val custom = chosen?.takeIf { it !in SleepTimer.PRESETS }
    var pickingCustom by remember { mutableStateOf(false) }

    if (pickingCustom) {
        CustomSleepTimerPage(
            palette = palette,
            initialMinutes = custom ?: 90,
            onBack = { pickingCustom = false },
            onStart = { minutes ->
                SleepTimer.start(minutes)
                onBack()
            },
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer),
            subtitle = when {
                countdown != null -> stringResource(R.string.pause_countdown, countdown)
                afterTrack -> stringResource(R.string.pause_after_song)
                else -> stringResource(R.string.pause_after_a_while)
            },
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        // Finishing the song is the one people reach for at the end of a
        // listen, so it leads rather than sitting under the durations.
        SleepOption(
            label = stringResource(R.string.after_this_song),
            selected = afterTrack,
            accent = palette.accent,
        ) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) {
                    stringResource(R.string.one_hour)
                } else {
                    pluralStringResource(R.plurals.minute_count, minutes, minutes)
                },
                selected = minutes == chosen,
                accent = palette.accent,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        SleepOption(
            label = custom?.let {
                stringResource(R.string.sleep_timer_custom) + " · " + sleepDuration(it)
            } ?: stringResource(R.string.sleep_timer_custom),
            selected = custom != null,
            accent = palette.accent,
        ) { pickingCustom = true }
        if (chosen != null || afterTrack) {
            ActionRow(SfIcons.Close, stringResource(R.string.turn_off_timer), accent = palette.accent) {
                SleepTimer.cancel()
                onBack()
            }
        }
    }
}

/**
 * A menu's pages, with the move between them animated: a page opened from another slides in from the right as the
 * one before it slides off to the left and fades, and going back runs the other way; the panel eases to the new
 * page's height rather than jumping to it. [depth] orders the pages — the deeper one is "forward".
 */
@Composable
internal fun <T> MenuPages(page: T, depth: (T) -> Int, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    androidx.compose.animation.AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val forward = depth(targetState) > depth(initialState)
            (
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 40))
                ).togetherWith(
                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { if (forward) -it / 4 else it / 4 } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ).using(androidx.compose.animation.SizeTransform(clip = true) { _, _ -> androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing) })
        },
        label = "menu_pages",
    ) { current -> content(current) }
}

/**
 * The top of a page inside a menu: a back arrow and the page's name. Every menu that groups its options into
 * pages opens them the same way, and goes back the same way.
 */
@Composable
internal fun MenuPageHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    if (subtitle != null) {
        SleepTimerHeader(title, subtitle, onBack)
        return
    }
    val colors = iosMenuColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(SfIcons.ArrowBack, contentDescription = stringResource(R.string.back), tint = colors.label)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.SemiBold),
            color = colors.label,
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = colors.separator, modifier = Modifier.padding(bottom = 4.dp))
}

/**
 * Back arrow, title and a line under it — the top of both sleep timer pages.
 * The title at the size of a menu row's label, as the heading of a menu's own
 * sub-page, not a page's.
 */
@Composable
private fun SleepTimerHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val colors = iosMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                SfIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = colors.label,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    letterSpacing = (-0.3).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.label,
            )
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                color = colors.secondaryLabel,
            )
        }
    }
}

/**
 * Any length up to twelve hours, set the way the player's lyrics offset is:
 * a large readout over the player's own thin slider, with − and + for a
 * single step either way. A page of this menu rather than an alert on top
 * of it, so it stays on the panel like the presets it sits with.
 */
@Composable
private fun CustomSleepTimerPage(
    palette: ArtworkPalette,
    initialMinutes: Int,
    onBack: () -> Unit,
    onStart: (Int) -> Unit,
) {
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    val foreground = MaterialTheme.colorScheme.onBackground
    val readout = iosMenuColors().label

    Column(Modifier.fillMaxWidth()) {
        SleepTimerHeader(
            title = stringResource(R.string.sleep_timer_custom),
            subtitle = stringResource(R.string.pause_after_a_while),
            onBack = onBack,
        )
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        Text(
            text = sleepDuration(minutes),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = readout,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StepButton(
                label = "−",
                contentDescription = stringResource(R.string.less),
                enabled = minutes > SleepTimer.STEP_MINUTES,
            ) { minutes -= SleepTimer.STEP_MINUTES }
            ThinSlider(
                value = SleepTimer.fractionOf(minutes),
                onValueChange = { minutes = SleepTimer.minutesAt(it) },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                activeColor = foreground.copy(alpha = 0.92f),
                inactiveColor = foreground.copy(alpha = 0.26f),
                modifier = Modifier.weight(1f),
            )
            StepButton(
                label = "+",
                contentDescription = stringResource(R.string.more),
                enabled = minutes < SleepTimer.MAX_MINUTES,
            ) { minutes += SleepTimer.STEP_MINUTES }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)
        ActionRow(SfIcons.SleepTimer, stringResource(R.string.start_sleep_timer), accent = palette.accent) {
            onStart(minutes)
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) iosMenuColors().label else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
        )
    }
}

/** "45 min", or "1h 25m" from an hour up. */
@Composable
private fun sleepDuration(minutes: Int): String = if (minutes >= 60) {
    stringResource(R.string.hours_minutes_short, minutes / 60, minutes % 60)
} else {
    stringResource(R.string.minutes_short, minutes)
}

@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    // The tick leads, in the glyph column, as a menu's picker marks its choice.
    IosMenuRow(
        label = label,
        icon = if (selected) SfIcons.Check else null,
        onClick = onClick,
        iconTint = accent,
    )
}

/** What the sleep timer row shows on the right, or null when none is armed. */
@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: stringResource(R.string.after_this_song).takeIf { afterTrack }
}

/** Live "m:ss" until the sleep timer fires, or null when none is running. */
@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/**
 * One line of an actions list: an iOS menu row on a menu panel, a full-width
 * row on the playlist picker's sheet — so the two read as the same control
 * rather than as two lists that happen to look alike.
 *
 * [tint] is for rows whose icon carries state — a filled heart on a liked
 * track — and is otherwise the ordinary foreground. [accent] colours the
 * trailing [value], and defaults to the app's own red; a surface with an
 * accent of its own passes that instead, so the row belongs to what it is
 * drawn on.
 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    /** A dimmer second line — only the popup draws it, as iOS menus do. */
    description: String? = null,
    /** Drawn where the icon goes, when the icon is a logo or a progress ring rather than a tinted vector. */
    leading: (@Composable () -> Unit)? = null,
    /** A chevron at the end: the row opens a page of related options instead of doing something. */
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    if (LocalActionRowStyle.current == ActionRowStyle.Menu) {
        IosMenuRow(
            label = label,
            icon = icon,
            onClick = onClick,
            description = description,
            value = value,
            iconTint = tint,
            valueColor = accent,
            enabled = enabled,
            leading = leading,
            trailing = if (chevron) {
                { Icon(SfIcons.ChevronRight, null, tint = iosMenuColors().secondaryLabel, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
        return
    }
    val foreground = MaterialTheme.colorScheme.onBackground
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (tint ?: foreground).copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = accent.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}

/**
 * Stands in for [ActionRow] while whether it belongs on the menu at all is
 * still unknown — "Open album" or "Open artist" before the lookup for their
 * ids has come back. A spinner rather than the row simply being missing, so
 * it doesn't read as decided against until it actually is.
 */
@Composable
private fun LoadingActionRow(icon: ImageVector, label: String, palette: ArtworkPalette) {
    IosMenuRow(label = label, icon = icon, onClick = null, enabled = false) {
        CircularProgressIndicator(
            color = palette.onBackgroundVariant,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The track the playlist picker is adding, drawn at its head — the same track
 * the menu it was opened from was about.
 *
 * [subtitleColor] is for ground other than the flat theme background, where
 * the usual dim grey may not hold.
 */
@Composable
internal fun SheetTrackHeader(
    song: Song,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .trackArtSize(52.dp, song.isVideo)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The heading over a sheet's second half — "Add to playlist". */
@Composable
internal fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}
