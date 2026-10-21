package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import dev.auden.dj.DjSession
import dev.auden.dj.DjUiState

/**
 * The DJ's request sheet, the chat of the DJ.
 *
 * Opened from the player's DJ button, and from the DJ card on Home. Typing says what to hear; "Surprise
 * me" lets the DJ choose. While the DJ is on, the same sheet steers it: more or less of what is playing,
 * or a new request from the next set on. The queue is not shown here, nor anywhere during a session:
 * the DJ decides what comes next, and the listener asks.
 */
@Composable
fun DjSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by DjSession.state.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val send: () -> Unit = {
        focus.clearFocus()
        DjSession.request(context, request)
        request = ""
        // The change is immediate: back to the player to hear it.
        onDismiss()
    }
    val surprise: () -> Unit = {
        focus.clearFocus()
        DjSession.request(context, "")
        request = ""
        onDismiss()
    }

    Column(modifier.fillMaxWidth().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dj_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(if (state.running) R.string.dj_now_playing_short else R.string.dj_subtitle, state.theme.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
            if (state.starting) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(14.dp))
                    Text(stringResource(R.string.dj_starting), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // The request, always: typed here it steers the DJ from the next set on.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (request.isEmpty()) {
                        Text(stringResource(R.string.dj_request_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = request,
                        onValueChange = { request = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (request.isNotBlank()) send() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (request.isNotEmpty()) {
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { request = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text(
                stringResource(R.string.dj_request_examples),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            state.problem?.let {
                Text(
                    stringResource(if (it == DjUiState.Problem.TOO_FEW_SONGS) R.string.dj_too_few else R.string.dj_no_player),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            if (state.running) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SteerPill(Icons.Rounded.ThumbUp, stringResource(R.string.dj_more_like_this), Modifier.weight(1f)) { DjSession.more() }
                    SteerPill(Icons.Rounded.ThumbDown, stringResource(R.string.dj_less_like_this), Modifier.weight(1f)) { DjSession.less() }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (request.isNotBlank()) send else surprise,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (request.isNotBlank()) R.string.dj_start else R.string.dj_surprise))
                }
                if (state.running) {
                    OutlinedButton(onClick = { DjSession.stop(); onDismiss() }) { Text(stringResource(R.string.dj_stop)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            VoiceLine(state.voice)
            state.speechError?.let {
                Text(
                    text = stringResource(R.string.dj_speech_error, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SteerPill(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** The DJ's voice, as the sheet states it: none for this language, a download to do, or ready. */
@Composable
private fun VoiceLine(voice: DjUiState.Voice) {
    val text = when (voice) {
        DjUiState.Voice.Unavailable -> stringResource(R.string.dj_voice_none)
        is DjUiState.Voice.NotInstalled -> stringResource(R.string.dj_voice_download, voice.pack.sizeLabel())
        is DjUiState.Voice.Downloading -> stringResource(R.string.dj_voice_downloading, (voice.fraction * 100).toInt())
        is DjUiState.Voice.Ready -> stringResource(R.string.dj_voice_ready, voice.pack.voice)
    }
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(enabled = voice is DjUiState.Voice.NotInstalled) { DjSession.downloadVoice(context) },
    )
    val failure = (voice as? DjUiState.Voice.NotInstalled)?.failure
    if (failure != null) {
        Text(
            text = stringResource(R.string.dj_voice_failed, failure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun dev.auden.dj.voice.VoicePacks.Pack.sizeLabel(): String = "%.0f MB".format(sizeBytes / 1_000_000.0)
