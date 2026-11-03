package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The DJ's look, after Spotify's: nothing on the page but one clean ring that moves with the voice, on the
 * same moving mesh the player uses for artwork.
 */

/**
 * The DJ's colours for the player's mesh: soft hues that the mesh then lifts into something rich. One
 * palette after another, so the page is never at rest.
 */
val DjMeshPalettes: List<MeshPalette> = listOf(
    MeshPalette(listOf(Color(0xFFFF8FC4), Color(0xFFFFB27A), Color(0xFFA98BFF), Color(0xFF6FC8FF))),
    MeshPalette(listOf(Color(0xFFA98BFF), Color(0xFF6FC8FF), Color(0xFF6FE3C0), Color(0xFFFF8FC4))),
    MeshPalette(listOf(Color(0xFF6FE3C0), Color(0xFFFFD86B), Color(0xFFFF8FA9), Color(0xFFD28BFF))),
    MeshPalette(listOf(Color(0xFF6FC8FF), Color(0xFF8FA6FF), Color(0xFFFF7FB8), Color(0xFFFFB27A))),
)

/**
 * Seconds, for as long as it is composed and [running]; read it in a draw block, never in composition, so
 * the frame it advances on invalidates the drawing and nothing else. Held still by "reduce animation".
 */
@Composable
internal fun rememberDjClock(running: Boolean = true): State<Float> {
    val reduce by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, reduce) {
        if (!running || reduce) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            seconds.floatValue += (now - last) / 1_000_000_000f
            last = now
        }
    }
    return seconds
}

/**
 * The loudness of the voice at the playhead, 0 to 1, eased: it rises fast with the voice and falls away
 * gently, and while [loading] it breathes slowly so the wait is not a still face. Read as a [State].
 */
@Composable
internal fun rememberVoiceLevel(
    envelope: FloatArray,
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    loading: Boolean,
): State<Float> {
    val level = remember { mutableFloatStateOf(0f) }
    val env = rememberUpdatedState(envelope)
    val playing = rememberUpdatedState(isPlaying)
    val busy = rememberUpdatedState(loading)
    val duration = rememberUpdatedState(durationMs)
    val position = rememberUpdatedState(positionMs)
    val clock = rememberDjClock()
    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            val e = env.value
            val t = clock.value
            val target = when {
                busy.value -> 0.14f + 0.08f * sin(t * 2.2f)
                !playing.value || e.isEmpty() || duration.value <= 0 -> 0f
                else -> {
                    val at = (position.value().toFloat() / duration.value * e.size).toInt().coerceIn(0, e.size - 1)
                    var sum = 0f
                    var n = 0
                    for (i in (at - 1)..(at + 2)) if (i in e.indices) { sum += e[i]; n++ }
                    if (n == 0) 0f else sum / n
                }
            }
            val rate = if (target > level.floatValue) 0.4f else 0.14f
            level.floatValue += (target - level.floatValue) * rate
        }
    }
    return level
}

/**
 * The DJ's voice as a ring: one thick, clean loop that wobbles as the voice rises, with echoes of itself
 * trailing behind in other colours, as in Spotify's DJ, but lit rather than flat — a white ring with a sweep
 * of pastel through it, over the moving mesh. At rest it breathes; [level] (0 to 1) is how far it is pushed
 * out of round. With [talking] it speaks on its own, for where there is no voice to follow (the card on Home).
 */
@Composable
fun DjRing(level: () -> Float, modifier: Modifier = Modifier, running: Boolean = true, talking: Boolean = false) {
    val clock = rememberDjClock(running)
    Canvas(modifier) {
        val t = clock.value
        val l = if (talking) {
            // Something like speech: bursts and pauses, never a steady wave.
            (0.14f + 0.6f * abs(sin(t * 2.7f) * sin(t * 1.1f + 0.6f))).coerceIn(0f, 1f)
        } else {
            level().coerceIn(0f, 1f)
        }
        val s = min(size.width, size.height)
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = s * 0.30f * (1f + 0.03f * l + 0.012f * sin(t * 1.4f))
        val stroke = s * 0.062f
        val push = 0.035f + 0.19f * l

        fun ring(time: Float): Path {
            val path = Path()
            val points = 140
            for (i in 0..points) {
                val a = 2f * PI.toFloat() * i / points
                val wobble = 0.55f * sin(2 * a + 0.9f * time + 0.3f) +
                    0.32f * sin(3 * a - 1.3f * time + 1.1f) +
                    0.22f * sin(4 * a + 1.7f * time + 2.0f) +
                    0.13f * sin(5 * a - 2.3f * time)
                val r = radius * (1f + push * wobble)
                val x = c.x + r * cos(a)
                val y = c.y + r * sin(a)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }

        // The echoes: the same ring a moment ago, a little off to one side, each in its own colour.
        val echoes = listOf(
            Triple(0.34f, Color(0xFF6EC8FF), 0.42f),
            Triple(0.20f, Color(0xFFFF8FC4), 0.52f),
            Triple(0.09f, Color(0xFF6FE3C0), 0.62f),
        )
        for ((lag, color, alpha) in echoes) {
            val drift = Offset(cos(t * 0.7f + lag * 3f), sin(t * 0.55f + lag * 5f)) * (s * 0.045f * (0.4f + l) * (lag / 0.34f))
            translate(drift.x, drift.y) {
                drawPath(ring(t - lag), color.copy(alpha = alpha), style = Stroke(stroke * 0.96f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val main = ring(t)
        // A soft glow under the ring, then the ring itself with colour moving round it.
        drawPath(main, Color.White.copy(alpha = 0.16f), style = Stroke(stroke * 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        rotate(degrees = t * 22f, pivot = c) {
            drawPath(
                main,
                brush = Brush.sweepGradient(
                    listOf(Color.White, Color(0xFFFFD6EC), Color.White, Color(0xFFD3E8FF), Color.White),
                    center = c,
                ),
                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
