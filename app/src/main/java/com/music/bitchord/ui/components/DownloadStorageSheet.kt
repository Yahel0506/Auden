package com.music.bitchord.ui.components

import android.text.format.Formatter
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import dev.auden.library.storage.DownloadAudit
import kotlinx.coroutines.launch

/**
 * What the download folder holds and whether it is tidy: how much, which files
 * are not where the folder layout puts them (with a button to move them, and one
 * to take it back), files nothing keeps a record of, and songs downloaded twice.
 */
@Composable
fun DownloadStorageSheet() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<DownloadAudit.Report?>(null) }
    var moving by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastResult by remember { mutableStateOf<DownloadAudit.MoveResult?>(null) }
    var canUndo by remember { mutableStateOf(DownloadAudit.canUndo(context)) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        report = null
        report = DownloadAudit.scan(context)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        Row(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    text = report?.let {
                        stringResource(R.string.storage_summary, it.downloads.size, Formatter.formatShortFileSize(context, it.totalBytes))
                    } ?: stringResource(R.string.storage_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        val r = report
        if (r == null) {
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
            return@Column
        }

        // ---- Folders ----
        val pending = r.misplaced
        StorageSection(
            icon = Icons.Rounded.Folder,
            title = stringResource(R.string.reorganize_title),
            subtitle = when {
                moving != null -> stringResource(R.string.reorganize_progress, moving!!.first, moving!!.second)
                lastResult != null -> stringResource(R.string.reorganize_done, lastResult!!.moved, lastResult!!.failed)
                pending.isEmpty() -> stringResource(R.string.reorganize_ok)
                else -> stringResource(R.string.reorganize_pending, pending.size)
            },
            action = stringResource(R.string.reorganize_action).takeIf { pending.isNotEmpty() && moving == null },
            onAction = {
                scope.launch {
                    moving = 0 to pending.size
                    lastResult = DownloadAudit.reorganize(context, pending) { done, total -> moving = done to total }
                    moving = null
                    canUndo = DownloadAudit.canUndo(context)
                    refresh++
                }
            },
        )
        if (canUndo && moving == null) {
            StorageSection(
                icon = Icons.Rounded.Undo,
                title = stringResource(R.string.reorganize_undo),
                subtitle = null,
                action = stringResource(R.string.reorganize_undo),
                onAction = {
                    scope.launch {
                        DownloadAudit.undo(context)
                        canUndo = false
                        lastResult = null
                        refresh++
                    }
                },
            )
        }

        // ---- Orphans ----
        if (r.orphans.isNotEmpty()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            StorageSection(
                icon = Icons.Rounded.FolderOff,
                title = stringResource(R.string.orphans_title),
                subtitle = stringResource(R.string.orphans_subtitle, r.orphans.size, Formatter.formatShortFileSize(context, r.orphans.sumOf { it.sizeBytes })),
                action = stringResource(R.string.delete),
                destructive = true,
                onAction = {
                    scope.launch {
                        DownloadAudit.deleteOrphans(context, r.orphans)
                        refresh++
                    }
                },
            )
        }

        // ---- Duplicates ----
        if (r.duplicates.isNotEmpty()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Text(
                stringResource(R.string.duplicates_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp),
            )
            Text(
                stringResource(R.string.duplicates_subtitle, r.duplicates.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            r.duplicates.forEach { group ->
                val keep = DownloadAudit.bestOf(group)
                StorageSection(
                    icon = Icons.Rounded.ContentCopy,
                    title = keep.meta.title,
                    subtitle = group.joinToString(" · ") { Formatter.formatShortFileSize(context, it.sizeBytes) },
                    action = stringResource(R.string.duplicates_keep_best),
                    onAction = {
                        scope.launch {
                            DownloadAudit.deleteExtras(context, group, keep)
                            refresh++
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    action: String?,
    onAction: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action != null, onClick = onAction)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}
