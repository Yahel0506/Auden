package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState

/**
 * What the song-information alert is asked to show. Held outside the menu that asks for it: the song menu lives in a
 * popover that clips and layers what it draws, and an alert drawn from inside it would be drawn there too. The host
 * ([SongInfoAlert]) sits with the app's other alerts, where one is drawn properly over everything.
 */
object SongInfoRequest {
    class Pending(val song: Song)

    var pending by mutableStateOf<Pending?>(null)
        private set

    fun show(song: Song) {
        pending = Pending(song)
    }

    fun close() {
        pending = null
    }
}

private val INFO_BODY_MAX_HEIGHT = 340.dp

/**
 * The song's information, as one of the app's alerts ([AlertScaffold]): its name and artist, what is known about it
 * as rows of the card, and the answers as capsules.
 */
@Composable
fun SongInfoAlert(hazeState: HazeState) {
    val request = SongInfoRequest.pending ?: return
    val song = request.song
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = SongInfoRepository.load(song)
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val unavailable = stringResource(R.string.song_info_unavailable)

    AlertScaffold(hazeState = hazeState, onDismiss = SongInfoRequest::close, width = 300.dp) {
        val close = animatedExit(SongInfoRequest::close)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.song_info),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = song.title,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            Modifier
                .heightIn(max = INFO_BODY_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            val rows = listOf(
                R.string.song_info_album to (info?.album ?: song.albumName),
                R.string.song_info_release_date to (info?.releaseDate ?: info?.releaseYear),
                R.string.song_info_views to info?.views?.let(::compactCount),
                R.string.song_info_likes to info?.likes,
                R.string.song_info_comments to info?.comments,
            )
            rows.forEach { (label, value) ->
                AlertRule(Modifier.padding(start = 20.dp))
                InfoRow(
                    label = stringResource(label),
                    value = value ?: unavailable.takeIf { info != null },
                )
            }
            if (info?.failed == true) {
                Text(
                    text = stringResource(R.string.song_info_error),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.padding(top = 14.dp))
        if (info?.failed == true) {
            AlertAction(label = stringResource(R.string.song_info_retry), emphasised = false, onClick = { retry++ })
        }
        AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = close)
    }
}

/** Label at the start, value at the end; a small spinner stands in for the value while it loads. */
@Composable
private fun InfoRow(label: String, value: String?) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = onSurface.copy(alpha = 0.6f),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = onSurface.copy(alpha = 0.5f),
                    strokeWidth = 1.75.dp,
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = onSurface,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
