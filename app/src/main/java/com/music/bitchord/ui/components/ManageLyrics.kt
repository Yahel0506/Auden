package com.music.bitchord.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsArtifact
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.LyricsSidecarStore
import com.music.bitchord.ui.theme.ArtworkPalette
import dev.auden.library.lyrics.LyricsFetcher
import dev.auden.library.lyrics.LyricsStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The lyrics kept for a song, under its download: each provider's version, which one is in use, a
 * preview, and a way to fetch another provider's.
 *
 * Choosing a version also rewrites the lyrics inside the downloaded file and beside it, so other players
 * show the same words Auden does.
 */
@Composable
internal fun ManageLyricsSection(song: Song, palette: ArtworkPalette) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = LyricsStore.key(song)
    var refresh by remember { mutableIntStateOf(0) }
    var variants by remember { mutableStateOf<List<LyricsStore.Stored>>(emptyList()) }
    var active by remember { mutableStateOf<LyricsSource?>(null) }
    var ownOffset by remember { mutableStateOf<Int?>(null) }
    var previewing by remember { mutableStateOf<LyricsSource?>(null) }
    var adding by remember { mutableStateOf(false) }
    val fetching = remember { mutableStateMapOf<LyricsSource, String>() }

    LaunchedEffect(key, refresh) {
        // A download made before variants existed has its lyrics only as a file beside the track.
        LyricsStore.importExisting(context, song)
        variants = LyricsStore.variants(context, key)
        active = LyricsStore.activeProvider(context, key)
        ownOffset = LyricsStore.offset(context, key)
    }
    // Whichever is in use when nothing was chosen: the first one kept, which is the one the download brought.
    val inUse = active ?: variants.firstOrNull()?.provider

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.lyrics_saved_title),
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
        )
        if (variants.isEmpty()) {
            Text(
                text = stringResource(R.string.lyrics_none_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
            )
        }
        variants.forEach { v ->
            val isActive = v.provider == inUse
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (isActive) palette.accent.copy(alpha = 0.14f) else palette.elevated)
                    .clickable { previewing = if (previewing == v.provider) null else v.provider }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.provider.label, style = MaterialTheme.typography.titleSmall, color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = listOf(
                                stringResource(
                                    when {
                                        v.wordSynced -> R.string.lyrics_fmt_word
                                        v.synced -> R.string.lyrics_fmt_line
                                        else -> R.string.lyrics_fmt_plain
                                    },
                                ),
                                stringResource(R.string.lyrics_lines_count, v.result.lines.size),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.onBackgroundVariant,
                        )
                    }
                    if (isActive) {
                        Icon(Icons.Rounded.Check, stringResource(R.string.lyrics_in_use), tint = palette.accent, modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                LyricsStore.setActive(context, key, v.provider)
                                v.result.artifact?.let { Downloads.applyLyricsToFiles(context, song, it) }
                                refresh++
                            }
                        }) { Text(stringResource(R.string.lyrics_use), color = palette.accent) }
                    }
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.delete),
                        tint = palette.onBackgroundVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                scope.launch {
                                    LyricsStore.delete(context, key, v.provider)
                                    refresh++
                                }
                            }
                            .padding(5.dp),
                    )
                }
                if (previewing == v.provider) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = v.result.lines.filter { it.text.isNotBlank() }.take(6).joinToString("\n") { it.text },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackgroundVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ownOffset?.let { offset ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lyrics_song_offset, String.format(Locale.getDefault(), "%+.1f s", offset / 1000f)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { LyricsStore.setOffset(context, key, null); refresh++ } }) {
                    Text(stringResource(R.string.lyrics_remove_offset), color = palette.accent)
                }
            }
        }

        // Another provider's version, fetched for this song whether or not it is playing.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { adding = !adding }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lyrics_add_provider),
                style = MaterialTheme.typography.titleSmall,
                color = palette.accent,
                modifier = Modifier.weight(1f),
            )
            Icon(if (adding) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = palette.accent)
        }
        if (adding) {
            val stored = variants.map { it.provider }.toSet()
            LyricsSource.offered.filter { it !in stored }.forEach { provider ->
                val state = fetching[provider]
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.label, style = MaterialTheme.typography.bodyLarge, color = palette.onBackground, modifier = Modifier.weight(1f))
                    when (state) {
                        "searching" -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = palette.accent)
                        "missing" -> Text(stringResource(R.string.lyrics_not_found), style = MaterialTheme.typography.labelMedium, color = palette.onBackgroundVariant)
                        else -> TextButton(onClick = {
                            fetching[provider] = "searching"
                            scope.launch {
                                val artifact: LyricsArtifact? = LyricsFetcher.fetch(song, provider)
                                if (artifact == null) {
                                    fetching[provider] = "missing"
                                } else {
                                    LyricsStore.save(context, key, artifact)
                                    fetching.remove(provider)
                                    refresh++
                                }
                            }
                        }) { Text(stringResource(R.string.download), color = palette.accent) }
                    }
                }
            }
        }
    }
}
