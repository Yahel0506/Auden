package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.theme.ArtworkPalette
import dev.auden.library.lyrics.LyricsStore
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

/**
 * Which lyrics are downloaded for a song, listed under its download the way its copies are: one card per
 * provider, the one in use outlined. Reading only — choosing, downloading and removing lyrics is done from
 * the player's lyrics provider sheet.
 */
@Composable
internal fun ManageLyricsSection(song: Song, palette: ArtworkPalette) {
    val context = LocalContext.current
    val key = LyricsStore.key(song)
    var variants by remember { mutableStateOf<List<LyricsStore.Stored>>(emptyList()) }
    var active by remember { mutableStateOf<LyricsSource?>(null) }
    var ownOffset by remember { mutableStateOf<Int?>(null) }

    suspend fun load() {
        // A download made before lyrics were kept per provider has them only as a file beside the track.
        LyricsStore.importExisting(context, song)
        variants = LyricsStore.variants(context, key)
        active = LyricsStore.activeProvider(context, key)
        ownOffset = LyricsStore.offset(context, key)
    }
    LaunchedEffect(key) { load() }
    // Kept current if they are changed from the player while this is open.
    LaunchedEffect(key) { LyricsStore.changes.collectLatest { if (it == key) load() } }

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
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive) palette.accent.copy(alpha = 0.14f) else palette.elevated)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                }
            }
        }
        ownOffset?.let { offset ->
            Text(
                text = stringResource(R.string.lyrics_song_offset, String.format(Locale.getDefault(), "%+.1f s", offset / 1000f)),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
            )
        }
    }
}
