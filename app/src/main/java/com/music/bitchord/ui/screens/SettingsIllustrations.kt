package com.music.bitchord.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.BackdropMotion
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.ThemeMode
import kotlin.math.PI
import kotlin.math.sin

/*
 * The small drawings Settings uses where a picture says it better than a line.
 *
 * They sit in the block they belong to, on the block's own surface, as one more element of it — no panel of their
 * own — and are drawn the way the rest of the block is: a single ink in three steps, no colour, chunky filled
 * shapes with round corners and no small detail. Gradients appear only where the thing drawn is one, as the
 * player's backdrops are.
 */

/** The drawing's whole palette: the ink and two quieter steps of it. */
private class Tone(val strong: Color, val mid: Color, val soft: Color) {
    companion object {
        fun of(color: Color) = Tone(color.copy(alpha = 0.9f), color.copy(alpha = 0.38f), color.copy(alpha = 0.14f))
    }
}

private val Paper = Color(0xFFF7F7F9)
private val Night = Color(0xFF17171B)
private val GrayInk = Color(0xFF8E8E93)

/**
 * Choices as tiles, each showing what it is: [preview] draws it, the label sits under it and the chosen one is
 * lifted on a soft plate. For the options that a word describes worse than a picture — a theme, a backdrop.
 */
@Composable
internal fun <T> PreviewTiles(
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    ratio: Float = 0.95f,
    preview: androidx.compose.ui.graphics.drawscope.DrawScope.(T) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = ROW_INSET - 6.dp, end = ROW_INSET - 6.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val on = item == selected
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(ratio)) { preview(item) }
                Text(
                    label(item),
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A drawing in its block, under the row it explains, on the block's own surface. */
@Composable
internal fun IllustrationCard(height: Dp = 80.dp, draw: DrawScope.() -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(start = ROW_INSET, end = ROW_INSET, top = 2.dp, bottom = 16.dp)
            .height(height),
    ) { draw() }
}

/**
 * The lyrics as the player sets them, with the blur the user has chosen: the same falloff tables the player uses
 * (alpha and blur by distance from the sung line, the blur scaled by [amount] / 75), on the player's own dark
 * surface, so the slider can be judged by looking rather than by its number.
 */
@Composable
internal fun LyricsBlurPreview(amount: Int) {
    val lines = listOf(
        R.string.lyrics_preview_1, R.string.lyrics_preview_2, R.string.lyrics_preview_3,
        R.string.lyrics_preview_4, R.string.lyrics_preview_5, R.string.lyrics_preview_6,
    ).map { stringResource(it) }
    val active = 1
    val alphas = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)
    val blurs = floatArrayOf(0f, 3.5f, 5f, 7.5f, 10f)
    val blurOn = android.os.Build.VERSION.SDK_INT >= 31
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = ROW_INSET - 6.dp, end = ROW_INSET - 6.dp, top = 4.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A46), Color(0xFF1B1B22)))),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lines.forEachIndexed { index, text ->
                val distance = kotlin.math.abs(index - active)
                val radius = if (distance == 0) 0f else blurs[distance.coerceAtMost(blurs.lastIndex)] * (amount / 75f)
                Text(
                    text,
                    color = Color.White.copy(alpha = alphas[distance.coerceAtMost(alphas.lastIndex)]),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = if (blurOn && radius > 0f) {
                        Modifier.blur(radius.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

// ---- Drawing helpers: everything is measured in [DrawScope.unit], a hundredth of the shorter side ----

private val DrawScope.unit: Float get() = minOf(size.width, size.height) / 100f

private fun DrawScope.fillRound(color: Color, topLeft: Offset, size: Size, radius: Float) =
    drawRoundRect(color, topLeft, size, CornerRadius(radius))

private fun DrawScope.bar(color: Color, from: Offset, to: Offset, width: Float) =
    drawLine(color, from, to, width, StrokeCap.Round)

/** A phone [height] tall, centred at ([cx], [cy]), as one filled shape; [content] draws on its screen, given the screen's rect. */
private fun DrawScope.phone(cx: Float, cy: Float, height: Float, body: Color, content: DrawScope.(Rect) -> Unit) {
    val width = height * 0.52f
    val left = cx - width / 2f
    val top = cy - height / 2f
    fillRound(body, Offset(left, top), Size(width, height), width * 0.24f)
    val inset = width * 0.09f
    content(Rect(left + inset, top + inset, left + width - inset, top + height - inset))
}

/** What every phone shows: a sleeve and two thick lines — [bleed] fills the top edge to edge, [sleeve] is its fill. */
private fun DrawScope.screenContent(screen: Rect, strong: Color, mid: Color, bleed: Boolean = false) {
    val w = screen.width
    val line = w * 0.13f
    if (bleed) {
        fillRound(strong, screen.topLeft, Size(w, w * 1.0f), w * 0.14f)
    } else {
        val s = w * 0.66f
        fillRound(strong, Offset(screen.left + (w - s) / 2f, screen.top + w * 0.14f), Size(s, s), s * 0.2f)
    }
    val textTop = screen.top + (if (bleed) w * 1.0f else w * 0.14f + w * 0.66f) + w * 0.22f
    bar(strong, Offset(screen.left + w * 0.2f, textTop), Offset(screen.left + w * 0.72f, textTop), line)
    bar(mid, Offset(screen.left + w * 0.2f, textTop + w * 0.26f), Offset(screen.left + w * 0.52f, textTop + w * 0.26f), line)
}

// ---- Theme ----

internal fun DrawScope.themePreview(mode: ThemeMode) {
    val height = size.height * 0.86f
    val cx = size.width / 2f
    val cy = size.height / 2f
    fun screen(dark: Boolean) {
        val body = if (dark) Night else Paper
        val strong = if (dark) Color(0xFFF2F2F7) else Color(0xFF2A2A2E)
        val mid = strong.copy(alpha = 0.4f)
        phone(cx, cy, height, body) { rect ->
            // A screen a shade off the body, so the phone reads as a phone in both.
            fillRound(if (dark) Color(0xFF26262B) else Color(0xFFEAEAEE), rect.topLeft, rect.size, rect.width * 0.2f)
            screenContent(rect, strong, mid)
        }
    }
    when (mode) {
        ThemeMode.LIGHT -> screen(false)
        ThemeMode.DARK -> screen(true)
        ThemeMode.SYSTEM -> {
            screen(false)
            // The right half of the same phone, in dark: the theme follows the system's.
            clipPath(Path().apply { addRect(Rect(cx, 0f, size.width, size.height)) }) { screen(true) }
        }
    }
}

// ---- Player backdrop ----

internal fun DrawScope.playerThemePreview(theme: PlayerTheme) {
    val tone = Tone.of(GrayInk)
    val height = size.height * 0.86f
    phone(size.width / 2f, size.height / 2f, height, tone.soft) { screen ->
        clipPath(Path().apply { addRoundRect(RoundRect(screen, CornerRadius(screen.width * 0.2f))) }) {
            when (theme) {
                // The cover's colours carried down from its edge and fading out.
                PlayerTheme.DEFAULT -> drawRect(
                    Brush.verticalGradient(listOf(GrayInk.copy(alpha = 0.7f), GrayInk.copy(alpha = 0.16f)), startY = screen.top, endY = screen.bottom),
                    screen.topLeft, screen.size,
                )
                // Soft shapes behind the whole screen.
                PlayerTheme.LEGACY -> for ((px, py, r) in listOf(Triple(0.2f, 0.28f, 0.8f), Triple(0.9f, 0.55f, 0.7f), Triple(0.3f, 0.95f, 0.75f))) {
                    val c = Offset(screen.left + screen.width * px, screen.top + screen.height * py)
                    drawCircle(Brush.radialGradient(listOf(GrayInk.copy(alpha = 0.55f), Color.Transparent), center = c, radius = screen.width * r), screen.width * r, c)
                }
                // One even tone.
                else -> drawRect(GrayInk.copy(alpha = 0.34f), screen.topLeft, screen.size)
            }
        }
        val w = screen.width
        val s = w * 0.6f
        fillRound(Color.White.copy(alpha = 0.92f), Offset(screen.left + (w - s) / 2f, screen.top + w * 0.16f), Size(s, s), s * 0.2f)
        bar(Color.White.copy(alpha = 0.92f), Offset(screen.left + w * 0.2f, screen.top + w * 0.16f + s + w * 0.24f), Offset(screen.left + w * 0.7f, screen.top + w * 0.16f + s + w * 0.24f), w * 0.13f)
    }
}

internal fun DrawScope.backdropMotionPreview(motion: BackdropMotion) {
    val tone = Tone.of(GrayInk)
    val w = size.width * 0.9f
    val h = size.height * 0.7f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRoundRect(Brush.horizontalGradient(listOf(GrayInk.copy(alpha = 0.18f), GrayInk.copy(alpha = 0.42f)), startX = left, endX = left + w), Offset(left, top), Size(w, h), CornerRadius(h * 0.34f))
    val stroke = unit * 7f
    val ink = Color.White.copy(alpha = 0.92f)
    when (motion) {
        // Nothing moves.
        BackdropMotion.STATIC -> bar(ink, Offset(left + w * 0.3f, top + h / 2f), Offset(left + w * 0.7f, top + h / 2f), stroke)
        // The colours drift: one slow wave.
        BackdropMotion.ANIMATED -> {
            val path = Path()
            for (i in 0..40) {
                val x = left + w * (0.2f + 0.6f * i / 40f)
                val y = top + h * (0.5f + 0.2f * sin(i / 40f * 2f * PI.toFloat()))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // They pulse with the music: three bars.
        else -> {
            val heights = floatArrayOf(0.34f, 0.62f, 0.44f)
            for (i in 0 until 3) {
                val x = left + w * (0.36f + 0.14f * i)
                bar(ink, Offset(x, top + h / 2f - h * heights[i] / 2f), Offset(x, top + h / 2f + h * heights[i] / 2f), stroke)
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = tone
}

// ---- Covers ----

/** The sleeve framed on its page, or filling the top of it: both drawn, the one in use in full ink. */
internal fun DrawScope.coverModePreview(fullBleed: Boolean, ink: Color, @Suppress("UNUSED_PARAMETER") panel: Color) {
    val tone = Tone.of(ink)
    val height = size.height * 0.92f
    val width = height * 0.52f
    val gap = width * 0.7f
    val start = (size.width - width * 2 - gap) / 2f
    fun screen(index: Int, bleed: Boolean, active: Boolean) {
        val strong = if (active) tone.strong else tone.mid
        val mid = if (active) tone.mid else tone.soft
        phone(start + width / 2f + index * (width + gap), size.height / 2f, height, tone.soft) { rect -> screenContent(rect, strong, mid, bleed) }
    }
    screen(0, bleed = false, active = !fullBleed)
    screen(1, bleed = true, active = fullBleed)
}

/** Three covers as the player draws a video's: its own 16:9, or cropped to a square. */
internal fun DrawScope.thumbnailPreview(square: Boolean, ink: Color) {
    val tone = Tone.of(ink)
    val w = size.width * 0.22f
    val h = if (square) w else w * 9f / 16f
    val tall = w
    val gap = w * 0.32f
    val left = (size.width - w * 3 - gap * 2) / 2f
    for (i in 0 until 3) {
        val x = left + i * (w + gap)
        val y = (size.height - tall) / 2f + (tall - h) / 2f - unit * 4f
        fillRound(if (i == 1) tone.strong else tone.mid, Offset(x, y), Size(w, h), w * 0.2f)
        bar(tone.soft, Offset(x + w * 0.12f, (size.height + tall) / 2f - unit * 4f + unit * 8f), Offset(x + w * 0.62f, (size.height + tall) / 2f - unit * 4f + unit * 8f), unit * 6f)
    }
}

// ---- Sound ----

/** Two songs crossing over: [seconds] sets how long they overlap; [smart] draws the longer one Automix picks. */
internal fun DrawScope.transitionPreview(seconds: Int, smart: Boolean, ink: Color) {
    val tone = Tone.of(ink)
    val mid = size.height / 2f
    val padX = size.width * 0.12f
    val songW = (size.width - padX * 2) * 0.58f
    val overlap = if (smart) songW * 0.5f else (songW * (seconds / 12f) * 0.8f).coerceAtLeast(unit * 6f)
    val stroke = unit * 7f
    val step = unit * 13f
    fun song(from: Float, fadeOut: Boolean) {
        val n = (songW / step).toInt().coerceAtLeast(2)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val a = size.height * 0.3f * (0.55f + 0.45f * (0.5f + 0.5f * sin(i * 1.5f)))
            val alpha = if (fadeOut) (1f - t * 0.7f) else (0.3f + t * 0.7f)
            bar(tone.strong.copy(alpha = 0.9f * alpha), Offset(from + songW * t, mid - a), Offset(from + songW * t, mid + a), stroke)
        }
    }
    val firstEnd = padX + songW
    song(padX, fadeOut = true)
    song(firstEnd - overlap, fadeOut = false)
}

/** A quiet passage and a loud one, as they are or brought to one level. */
internal fun DrawScope.loudnessPreview(on: Boolean, ink: Color) {
    val tone = Tone.of(ink)
    val n = 11
    val left = size.width * 0.14f
    val w = size.width * 0.72f
    val mid = size.height / 2f
    val stroke = unit * 8f
    for (i in 0 until n) {
        val loud = i >= n / 2 + 1
        val level = (if (on) 0.5f else if (loud) 0.95f else 0.3f) * (0.75f + 0.25f * sin(i * 1.9f))
        val x = left + w * i / (n - 1f)
        bar(if (loud) tone.strong else tone.mid, Offset(x, mid - size.height * 0.4f * level), Offset(x, mid + size.height * 0.4f * level), stroke)
    }
}

/** Music with a gap in it, left alone or closed. */
internal fun DrawScope.skipSilencePreview(on: Boolean, ink: Color) {
    val tone = Tone.of(ink)
    val mid = size.height / 2f
    val left = size.width * 0.14f
    val w = size.width * 0.72f
    val stroke = unit * 8f
    val step = unit * 15f
    var x = left
    fun burst(width: Float) {
        val end = x + width
        var i = 0
        while (x < end) {
            val a = size.height * 0.3f * (0.6f + 0.4f * (0.5f + 0.5f * sin(i * 1.3f)))
            bar(tone.strong, Offset(x, mid - a), Offset(x, mid + a), stroke)
            x += step; i++
        }
    }
    burst(w * 0.34f)
    if (on) {
        x += step * 0.4f
    } else {
        bar(tone.mid, Offset(x - step * 0.3f, mid), Offset(x + w * 0.2f, mid), stroke)
        x += w * 0.24f
    }
    burst(w * 0.34f)
}
