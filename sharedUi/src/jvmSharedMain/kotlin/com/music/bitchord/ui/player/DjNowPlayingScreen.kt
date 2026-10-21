package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The player while the DJ speaks: no artwork and no queue, a waveform of the line instead, the
 * words under it, and the controls the DJ needs — pause, skip the line, and a way to ask for
 * something else.
 *
 * Shown in place of the song player for any DJ clip (see [Song.isDjClip]). The waveform is the
 * clip's own loudness, computed when the line was rendered, so the cover moves with the voice
 * without any analysis on the playback thread.
 */
@Composable
fun DjNowPlayingScreen(
    /** The line being said, as written; null when none is known. */
    words: String?,
    /** The loudness of the line over its length, 0..1. Empty when there is none. */
    envelope: FloatArray,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
    onAsk: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15161A), Color(0xFF050506))))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(Res.string.dj_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(36.dp))

            // The cover: the voice's loudness over the length of the line, the part already said lit.
            val progress = if (durationMs > 0) (position.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val lift by animateFloatAsState(
                targetValue = if (isPlaying) 1f else 0.6f,
                animationSpec = tween(400),
                label = "djWaveLift",
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            ) {
                drawWave(envelope, progress, lift, accent)
            }

            Spacer(Modifier.height(28.dp))
            // The words of the line, as they are said. Empty when no voice exists for the language.
            Text(
                text = words ?: stringResource(Res.string.dj_no_words),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DjPill(label = stringResource(Res.string.dj_ask), onClick = onAsk)
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPlayPause,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }
                DjPill(label = stringResource(Res.string.dj_skip_line), onClick = onSkip, icon = Icons.Rounded.SkipNext)
            }
        }
    }
}

@Composable
private fun DjPill(label: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** The waveform: one bar per envelope value, centred, the part already said in white and the rest dimmed. */
private fun DrawScope.drawWave(envelope: FloatArray, progress: Float, lift: Float, accent: Color) {
    if (envelope.isEmpty()) {
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        return
    }
    val gap = size.width / envelope.size
    val barWidth = gap * 0.56f
    val mid = size.height / 2
    val said = (progress * envelope.size).toInt()
    for (i in envelope.indices) {
        val h = (envelope[i] * size.height * 0.92f * lift).coerceAtLeast(3f)
        val x = i * gap + (gap - barWidth) / 2
        val color = when {
            i < said -> Color.White
            i == said -> accent
            else -> Color.White.copy(alpha = 0.28f)
        }
        drawRoundRect(color, Offset(x, mid - h / 2), androidx.compose.ui.geometry.Size(barWidth, h), androidx.compose.ui.geometry.CornerRadius(barWidth / 2))
    }
}
