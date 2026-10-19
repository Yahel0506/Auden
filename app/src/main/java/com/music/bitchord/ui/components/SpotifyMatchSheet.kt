package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song

/**
 * Picks the right YouTube Music song for a Spotify track whose automatic match was wrong or missing.
 * Searches as the listener types and hands back the song they tap; the choice is kept, so it survives
 * every later open and sync.
 */
@Composable
fun SpotifyMatchSheet(
    row: Song,
    onPick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("${row.title} ${row.artist}".trim()) }
    var results by remember { mutableStateOf<List<Song>?>(null) }
    var search by remember { mutableIntStateOf(0) }

    LaunchedEffect(search) {
        results = null
        results = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
    }

    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            stringResource(R.string.spotify_fix_match),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Text(
            stringResource(R.string.spotify_fix_match_hint, row.title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.spotify_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search++ }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        val list = results
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
            list.isEmpty() -> Text(
                stringResource(R.string.spotify_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(22.dp),
            )
            else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.videoId }) { song ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(song) }
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(song.artist, song.durationText).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
