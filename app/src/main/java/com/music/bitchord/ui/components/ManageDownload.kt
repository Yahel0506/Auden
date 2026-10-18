package com.music.bitchord.ui.components

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.theme.ArtworkPalette
import dev.auden.library.links.LocalCopy

/**
 * "Manage download": one row that opens, in place, the list of files on this
 * device that are the song — the app's own download and any other file with the
 * same metadata. The one being played is outlined in the sheet's accent.
 *
 * Lyrics management will live under the same row later; for now it is the file
 * list and the way to delete the app's own download.
 */
@Composable
internal fun ManageDownloadRow(
    copies: List<LocalCopy>,
    playingUri: String?,
    palette: ArtworkPalette,
    onDeleteOwn: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ActionRow(
        icon = Icons.Rounded.DownloadDone,
        label = stringResource(R.string.manage_download),
        value = if (copies.size > 1) stringResource(R.string.download_copies_count, copies.size) else null,
        tint = palette.accent,
        accent = palette.accent,
    ) { expanded = !expanded }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            copies.forEach { copy ->
                DownloadCopyCard(
                    copy = copy,
                    playing = playingUri != null && playingUri == copy.uri,
                    palette = palette,
                    onDelete = onDeleteOwn.takeIf { copy.downloadedByApp },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DownloadCopyCard(
    copy: LocalCopy,
    playing: Boolean,
    palette: ArtworkPalette,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (playing) palette.accent.copy(alpha = 0.14f) else palette.elevated)
            .then(if (playing) Modifier.border(BorderStroke(1.5.dp, palette.accent), shape) else Modifier)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (playing) palette.accent else palette.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(copy.artist, copy.album).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.copy_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.accent,
                )
            }
        }
        val facts = listOfNotNull(
            copy.format,
            copy.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
            copy.durationText,
            stringResource(if (copy.downloadedByApp) R.string.copy_downloaded_by_app else R.string.copy_found_on_device),
        ).joinToString(" · ")
        Spacer(Modifier.height(8.dp))
        Text(
            text = facts,
            style = MaterialTheme.typography.labelMedium,
            color = palette.onBackgroundVariant,
        )
        // Where it is. A file kept in the app's own storage has no path anyone else can open.
        Text(
            text = if (copy.inAppStorage) stringResource(R.string.copy_in_app_storage) else (copy.path ?: copy.uri),
            style = MaterialTheme.typography.labelMedium,
            color = palette.onBackgroundVariant.copy(alpha = 0.8f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        onDelete?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.delete_download),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickableNoRipple(it)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
