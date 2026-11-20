package com.music.bitchord.ui.screens

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.music.bitchord.R
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.playback.AudioCache
import java.io.File
import java.util.Locale

/**
 * What Auden holds on the device, measured. Laid out after Mochy Music's storage page (GPLv3, like this app): one
 * bar for the whole phone, one for what the app itself keeps, and a legend with the size of each part.
 */
internal class StorageUsage(
    val total: Long,
    val free: Long,
    val songCache: Long,
    val images: Long,
    val downloads: Long,
    val database: Long,
    val other: Long,
) {
    val app: Long get() = songCache + images + downloads + database + other
    val othersUsed: Long get() = (total - free - app).coerceAtLeast(0L)
}

/** Walks the app's own folders: call it off the main thread. */
internal fun measureStorage(context: Context): StorageUsage {
    val stat = StatFs(Environment.getDataDirectory().path)
    val songCache = AudioCache.usedBytes()
    val images = SingletonImageLoader.get(context).diskCache?.size ?: 0L
    val downloads = runCatching { DownloadStore.listAudio(context).sumOf { it.sizeBytes } }.getOrDefault(0L)
    val database = dirSize(File(context.dataDir, "databases"))
    val privateDownloads = dirSize(File(context.filesDir, "downloads"))
    val other = (dirSize(context.dataDir) - database - songCache - images - privateDownloads).coerceAtLeast(0L)
    return StorageUsage(stat.totalBytes, stat.availableBytes, songCache, images, downloads, database, other)
}

private fun dirSize(file: File): Long =
    if (file.isFile) file.length() else file.listFiles()?.sumOf { dirSize(it) } ?: 0L

/** Powers of 1000, one decimal below 10, as Android's own storage screen writes them. */
internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val number = if (value >= 10 || unit == 0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    return "$number ${units[unit]}"
}

private val CacheOrange = Color(0xFFF5B26B)
private val ImagesYellow = Color(0xFFE8D26A)
private val DatabaseRed = Color(0xFFEE6E5E)
private val AppDataBlue = Color(0xFF6878F0)

private class BarPart(val color: Color, val bytes: Long)

/** The two cards at the top of Storage, and the nudge under them. [usage] is null while it is being measured. */
@Composable
internal fun StorageOverview(usage: StorageUsage?) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    SettingsGroup(topSpacing = 0.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(R.string.storage_overall),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(14.dp))
            UsageBar(
                parts = listOf(BarPart(accent, usage?.app ?: 0), BarPart(muted, usage?.othersUsed ?: 0)),
                total = usage?.total ?: 0,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Swatch(accent)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.storage_legend_auden), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(16.dp))
                Swatch(muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.storage_legend_other),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (usage != null) {
                    Text(
                        text = stringResource(R.string.storage_free, formatBytes(usage.free)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val hint = usage?.let { u ->
        val nearlyFull = u.total > 0 && u.free * 10 < u.total
        when {
            u.songCache + u.images > 50_000_000L && u.songCache + u.images >= u.downloads ->
                stringResource(R.string.storage_hint_cache, formatBytes(u.songCache + u.images))
            nearlyFull -> stringResource(R.string.storage_hint_full)
            else -> null
        }
    }
    SettingsGroup(footer = hint, topSpacing = 14.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = usage?.let {
                    stringResource(R.string.storage_uses, percentOf(it.app, it.total), formatBytes(it.app))
                } ?: stringResource(R.string.storage_measuring),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(14.dp))
            UsageBar(
                parts = listOf(
                    BarPart(CacheOrange, usage?.songCache ?: 0),
                    BarPart(ImagesYellow, usage?.images ?: 0),
                    BarPart(accent, usage?.downloads ?: 0),
                    BarPart(DatabaseRed, usage?.database ?: 0),
                    BarPart(AppDataBlue, usage?.other ?: 0),
                ),
                total = usage?.app ?: 0,
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendRow(CacheOrange, stringResource(R.string.settings_block_song_cache), usage?.songCache)
                LegendRow(ImagesYellow, stringResource(R.string.settings_block_image_cache), usage?.images)
                LegendRow(accent, stringResource(R.string.downloads), usage?.downloads)
                LegendRow(DatabaseRed, stringResource(R.string.storage_legend_database), usage?.database)
                LegendRow(AppDataBlue, stringResource(R.string.storage_legend_app_data), usage?.other)
            }
        }
    }
}

/** 1.4%, or <0.1% for a sliver, so a small app never reads as using nothing. */
private fun percentOf(part: Long, total: Long): String {
    if (total <= 0L) return "0%"
    val p = part * 100.0 / total
    return when {
        p < 0.1 -> "<0.1%"
        p < 10 -> String.format(Locale.US, "%.1f%%", p)
        else -> "${p.toInt()}%"
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color),
    )
}

@Composable
private fun LegendRow(color: Color, label: String, bytes: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Swatch(color)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (bytes != null) {
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A rounded bar split into parts over [total]; a part too small to see keeps a sliver so the legend has no ghost. */
@Composable
private fun UsageBar(parts: List<BarPart>, total: Long) {
    val grow by animateFloatAsState(if (total > 0) 1f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "usageBarGrow")
    val fractions = parts.map { part ->
        animateFloatAsState(
            targetValue = if (total > 0) (part.bytes.toFloat() / total).coerceIn(0f, 1f) else 0f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 300f),
            label = "usageBarPart",
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            .drawBehind {
                val minSliver = 3.dp.toPx()
                var x = 0f
                parts.forEachIndexed { i, part ->
                    if (part.bytes <= 0L) return@forEachIndexed
                    val width = (fractions[i].value * size.width).coerceAtLeast(minSliver) * grow
                    drawRect(
                        color = part.color,
                        topLeft = Offset(x, 0f),
                        size = Size(width.coerceAtMost(size.width - x), size.height),
                    )
                    x += width
                }
            },
    )
}
