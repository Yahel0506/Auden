package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import dev.auden.library.io.PlaylistFormat
import dev.chrisbanes.haze.HazeState

/**
 * The formats a playlist can be exported as, in the app's frosted alert in the middle of the screen: each with a
 * line on what it is for, and choosing one is the action — the file dialog opens at once. Raised by the playlist
 * menu, which closes first, as the delete confirmation does.
 */
@Composable
fun FrostedExportPicker(
    hazeState: HazeState,
    onPick: (PlaylistFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, width = 300.dp, blurBackdrop = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.export_playlist_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.export_playlist_hint),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        FormatRow(PlaylistFormat.M3U8, R.string.export_format_m3u8, R.string.export_format_m3u8_detail, onPick)
        FormatRow(PlaylistFormat.XSPF, R.string.export_format_xspf, R.string.export_format_xspf_detail, onPick)
        FormatRow(PlaylistFormat.AUDEN, R.string.export_format_auden, R.string.export_format_auden_detail, onPick)
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
    }
}

@Composable
private fun FormatRow(format: PlaylistFormat, name: Int, detail: Int, onPick: (PlaylistFormat) -> Unit) {
    AlertRule()
    val pick = animatedExit { onPick(format) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = pick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(name),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(detail),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}
