package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.dj_ask
import com.music.bitchord.sharedui.resources.dj_no_words
import com.music.bitchord.sharedui.resources.dj_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The player while the DJ speaks. It is the main player, laid out the same way: the cover where the
 * artwork goes, the title and its line beneath, the transport, the bottom row with the DJ's button
 * where the queue's would be.
 *
 * The cover is not a progress bar. It is the voice made visible: bars that rise and fall with how
 * loud the DJ is at that moment, read off the loudness shape computed when the line was rendered,
 * and that settle when it pauses. What is said is written beneath, so nothing depends on hearing it.
 */
@Composable
fun DjNowPlayingScreen(
    /** The line being said, as written; null when none is known. */
    words: String?,
    /** What the current set is about, shown under the DJ's name. */
    theme: String?,
    /** The loudness of the line over its length, 0..1. Empty when there is none. */
    envelope: FloatArray,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
    onAsk: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A1B20), Color(0xFF050506))))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = PLAYER_GUTTER),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            // The cover, square like every cover, with the voice in it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3A3F55), Color(0xFF14151C)))),
            ) {
                VoiceWave(envelope, position, durationMs, isPlaying, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.dj_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
                if (theme != null) {
                    Text(
                        text = theme,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = words ?: stringResource(Res.string.dj_no_words),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            // The same transport as the player's. Back has nothing to step to, so it rests.
            TransportRow(
                isPlaying = isPlaying,
                isLoading = false,
                previousEnabled = false,
                nextEnabled = true,
                onPrevious = {},
                onPlayPause = onPlayPause,
                onNext = onSkip,
            )
            Spacer(Modifier.height(26.dp))
            // Where the queue's button is in the player, the DJ's: the way to ask for something else.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                BottomGlyph(
                    icon = Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(Res.string.dj_ask),
                    onClick = onAsk,
                )
            }
        }
    }
}

/**
 * Bars that follow the voice. The level is how loud the line is at the playhead, which is carried
 * forward on the frame clock between the player's readings so the movement is continuous; each bar
 * wobbles on its own phase and is strongest in the middle, so a steady level still looks alive and a
 * pause lets everything settle to a line.
 */
@Composable
private fun VoiceWave(
    envelope: FloatArray,
    position: PlaybackPosition,
    durationMs: Long,
    isPlaying: Boolean,
    modifier: Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    val level = remember { FloatArray(1) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { frame = it }
    }
    Canvas(modifier) {
        val t = frame / 1_000_000_000f
        val posMs = position.positionMs + if (isPlaying) (System.nanoTime() - position.sampledAtNanos) / 1_000_000L else 0L
        val target = if (!isPlaying || envelope.isEmpty() || durationMs <= 0) {
            0f
        } else {
            val at = (posMs.toFloat() / durationMs * envelope.size).toInt().coerceIn(0, envelope.size - 1)
            var sum = 0f
            var n = 0
            for (i in (at - 1)..(at + 2)) if (i in envelope.indices) { sum += envelope[i]; n++ }
            if (n == 0) 0f else sum / n
        }
        // Eased towards the target: it rises fast when the voice does and falls away gently.
        val rate = if (target > level[0]) 0.35f else 0.12f
        level[0] += (target - level[0]) * rate

        val bars = 29
        val gap = size.width / bars
        val barWidth = gap * 0.46f
        val mid = size.height / 2
        val centre = (bars - 1) / 2f
        // A glow behind the bars that breathes with the level.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.10f + 0.22f * level[0]), Color.Transparent),
                center = Offset(size.width / 2, mid),
                radius = size.width * (0.35f + 0.25f * level[0]),
            ),
            radius = size.width * 0.6f,
            center = Offset(size.width / 2, mid),
        )
        for (i in 0 until bars) {
            val shape = exp(-(((i - centre) / (bars * 0.30f)).let { it * it })).toFloat()
            val wobble = 0.55f + 0.45f * sin(t * 7f + i * 0.62f)
            val h = (size.height * 0.05f + size.height * 0.80f * level[0] * wobble * (0.35f + 0.65f * shape))
                .coerceAtLeast(size.height * 0.02f)
            val x = i * gap + (gap - barWidth) / 2
            drawRoundRect(
                color = Color.White.copy(alpha = (0.55f + 0.45f * shape).coerceIn(0f, 1f)),
                topLeft = Offset(x, mid - h / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}
