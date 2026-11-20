package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.screens.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** How many of the heaviest downloads are set apart at the top. */
private const val LARGEST = 5

private class DownloadItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val bytes: Long,
)

/**
 * Every download with its size, searchable. The heaviest few lead the list on their own, apart from the rest; tapping
 * rows picks them and the button at the foot removes the pick. After Mochy Music's Manage downloads sheet (GPLv3).
 */
@Composable
fun ManageDownloadsSheet(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val items by produceState<List<DownloadItem>?>(null, saved) {
        value = withContext(Dispatchers.IO) {
            val meta = Downloads.savedMetadata.value
            saved.map { (id, uri) ->
                val m = meta[id]
                DownloadItem(
                    videoId = id,
                    title = m?.title?.takeIf { it.isNotBlank() } ?: id,
                    artist = m?.artist.orEmpty(),
                    thumbnail = m?.thumbnailUrl,
                    bytes = runCatching { DownloadStore.sizeOf(context, uri.toUri()) }.getOrDefault(0L),
                )
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val picks = remember { mutableStateListOf<String>() }
    var confirming by remember { mutableStateOf(false) }

    val all = items
    val needle = query.trim()
    val bySize = remember(all) { all.orEmpty().sortedByDescending { it.bytes } }
    val largest = remember(bySize) { if (bySize.size > LARGEST) bySize.take(LARGEST) else emptyList() }
    val rest = remember(bySize, largest) {
        bySize.drop(largest.size).sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    val matches = remember(bySize, needle) {
        if (needle.isEmpty()) emptyList()
        else bySize.filter { it.title.contains(needle, true) || it.artist.contains(needle, true) }
    }
    val picked = bySize.filter { it.videoId in picks }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
        com.music.bitchord.ui.components.SearchField(
            query = query,
            onQueryChange = { query = it },
            onSubmit = {},
            placeholder = stringResource(R.string.manage_downloads_search),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            text = all?.let { stringResource(R.string.manage_downloads_summary, it.size, formatBytes(it.sumOf { d -> d.bytes })) }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 10.dp),
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)) {
            when {
                all == null -> Unit
                all.isEmpty() -> item { Note(stringResource(R.string.manage_downloads_empty)) }
                needle.isNotEmpty() ->
                    if (matches.isEmpty()) item { Note(stringResource(R.string.manage_downloads_no_match, needle)) }
                    else items(matches, key = { "m-${it.videoId}" }) { Row(it, it.videoId in picks) { toggle(picks, it.videoId) } }
                else -> {
                    if (largest.isNotEmpty()) {
                        item { Label(stringResource(R.string.manage_downloads_largest)) }
                        items(largest, key = { "l-${it.videoId}" }) { Row(it, it.videoId in picks) { toggle(picks, it.videoId) } }
                        item { Spacer(Modifier.height(18.dp)) }
                        item { Label(stringResource(R.string.manage_downloads_all)) }
                    }
                    items(rest, key = { "r-${it.videoId}" }) { Row(it, it.videoId in picks) { toggle(picks, it.videoId) } }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { if (picked.isEmpty()) onDone() else confirming = true }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (picked.isEmpty()) stringResource(R.string.manage_downloads_done)
                else stringResource(R.string.manage_downloads_remove, picked.size, formatBytes(picked.sumOf { it.bytes })),
                style = MaterialTheme.typography.bodyLarge,
                color = if (picked.isEmpty()) MaterialTheme.colorScheme.onBackground else RemoveRed,
            )
        }
    }

    if (confirming) {
        GlassAlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.manage_downloads_confirm_title, picked.size)) },
            text = { Text(stringResource(R.string.manage_downloads_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    val ids = picked.map { it.videoId }
                    picks.clear()
                    scope.launch { ids.forEach { Downloads.delete(context, it) } }
                }) { Text(stringResource(R.string.manage_downloads_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal val RemoveRed = androidx.compose.ui.graphics.Color(0xFFFF453A)

private fun toggle(picks: MutableList<String>, id: String) {
    if (!picks.remove(id)) picks.add(id)
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun Row(item: DownloadItem, picked: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.artist.isNotBlank()) {
                Text(item.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatBytes(item.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
    }
}
