package com.music.bitchord.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.mix_badge_new
import com.music.bitchord.ui.player.rememberDjClock
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The colours one of the generated lists wears: the two of its cover's gradient, which also tint the page it opens
 * (as every list's page is tinted by its cover), and the eyebrow on its card.
 */
data class MixStyle(val top: Color, val bottom: Color, val accent: Color, val eyebrow: Color)

fun TopPickKind.mixStyle(): MixStyle = when (this) {
    TopPickKind.IN_LOOP -> MixStyle(Color(0xFFFF4F6D), Color(0xFFFFB36B), Color(0xFFFF4F6D), Color(0xFFFFE6D9))
    TopPickKind.NEW_FOR_YOU -> MixStyle(Color(0xFF7B2FF7), Color(0xFFFF5FA8), Color(0xFFD9FF4A), Color(0xFFF2E6FF))
    TopPickKind.LIKES_MIX -> MixStyle(Color(0xFFFF1F4B), Color(0xFFFF8F7A), Color(0xFFFF1F4B), Color(0xFFFFE1E1))
    TopPickKind.BECAUSE_YOU_LIKE -> MixStyle(Color(0xFF2F80FF), Color(0xFF2FD3FF), Color(0xFF2F80FF), Color(0xFFDDF2FF))
    TopPickKind.GENRE_MIX -> MixStyle(Color(0xFF0FA37F), Color(0xFF5BE38C), Color(0xFF0FA37F), Color(0xFFDDFFEC))
    TopPickKind.DEEP_CUTS -> MixStyle(Color(0xFF1F8FD6), Color(0xFF7FD8F2), Color(0xFF1F8FD6), Color(0xFFE0F6FF))
    TopPickKind.REDISCOVER -> MixStyle(Color(0xFFFF8A00), Color(0xFFFFD43B), Color(0xFFFF8A00), Color(0xFFFFF1CC))
    else -> MixStyle(Color(0xFF9B7BE8), Color(0xFFF7A6D8), Color(0xFF9B7BE8), Color(0xFFF6EAFF))
}

/** The small line above a generated list's title: its colour, matched to its cover. */
fun TopPickKind.mixEyebrow(): Color = mixStyle().eyebrow

/** The kind of a generated list's page, read off its id (`local:mix:loop` …); null for any other page. */
fun mixKindOf(pageId: String?): TopPickKind? = when (pageId) {
    "local:mix:loop" -> TopPickKind.IN_LOOP
    "local:mix:genre" -> TopPickKind.GENRE_MIX
    "local:mix:new" -> TopPickKind.NEW_FOR_YOU
    "local:mix:because" -> TopPickKind.BECAUSE_YOU_LIKE
    "local:mix:likes" -> TopPickKind.LIKES_MIX
    "local:mix:rediscover" -> TopPickKind.REDISCOVER
    "local:mix:deep" -> TopPickKind.DEEP_CUTS
    "local:mix:year" -> TopPickKind.ONE_YEAR_AGO
    else -> null
}

/**
 * The cover of one of the lists Auden makes each day. One bright gradient, one idea, slow movement — nothing else:
 *
 * - **In loop**: the most repeated song in a circle, a loop arrow turning round it.
 * - **New music**: a fanned deck of new sleeves dealing itself, a "new" sticker on top.
 * - **Your likes**: a heart with a liked song in it, beating.
 * - **Because you like**: the song, with its sleeve's echoes drifting behind it.
 * - **Genre mix**: the genre in big type running in rows, two of its artists over it.
 * - **Deep cuts**: the artist, close and slowly closer.
 * - **Rediscover**: four old favourites in a grid, one changing at a time.
 * - **One year ago**: the year in big type, three sleeves of it pinned under it.
 *
 * The same cover on the big card on Home and at the head of the list's page. With [captionSpace] (the card) it keeps
 * to the upper part and shades the foot for the caption; without it (the page) it fills the square. [label] is what
 * some covers write: the genre, the year.
 */
@Composable
fun MixCoverArt(
    kind: TopPickKind,
    images: List<String>,
    modifier: Modifier = Modifier,
    label: String = "",
    motion: Boolean = LocalArtworkMotion.current,
    captionSpace: Boolean = true,
) {
    val style = kind.mixStyle()
    val clock = rememberDjClock(motion)
    Box(modifier.background(Brush.linearGradient(listOf(style.top, style.bottom)))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stage = Stage(maxWidth, if (captionSpace) maxHeight * 0.66f else maxHeight)
            when (kind) {
                TopPickKind.IN_LOOP -> LoopArt(images, stage, clock)
                TopPickKind.NEW_FOR_YOU -> DeckArt(images, style, stage, clock)
                TopPickKind.LIKES_MIX -> HeartArt(images, stage, clock)
                TopPickKind.BECAUSE_YOU_LIKE -> EchoArt(images, stage, clock)
                TopPickKind.GENRE_MIX -> GenreArt(images, label, stage, clock)
                TopPickKind.DEEP_CUTS -> ArtistArt(images, stage, clock)
                TopPickKind.REDISCOVER -> GridArt(images, stage, clock)
                else -> YearArt(images, label, stage, clock)
            }
        }
        if (captionSpace) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.42f))),
            )
        }
    }
}

private class Stage(val width: Dp, val height: Dp) {
    val short: Dp get() = minOf(width, height)
}

/** The clock's step every [period] seconds, read so the composable recomposes once a step and not every frame. */
@Composable
private fun rememberStep(clock: State<Float>, period: Float): State<Int> =
    remember(period) { derivedStateOf { floor(clock.value / period).toInt() } }

private val Sleeve = RoundedCornerShape(14)

// ---- In loop ------------------------------------------------------------------------------------------------

@Composable
private fun LoopArt(images: List<String>, stage: Stage, clock: State<Float>) {
    val cover = stage.short * 0.50f
    val ring = stage.short * 0.78f
    val cx = stage.width / 2
    val cy = stage.height * 0.52f
    Canvas(
        Modifier
            .offset(cx - ring / 2, cy - ring / 2)
            .size(ring)
            .graphicsLayer { rotationZ = (clock.value * 30f) % 360f },
    ) {
        val stroke = size.width * 0.06f
        val inset = stroke / 2 + stroke * 0.6f
        val arc = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(Color.White, -90f, 290f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        // The arrowhead at the end of the arc, pointing on round it.
        val r = arc.width / 2
        val end = (-90f + 290f) * (PI / 180f).toFloat()
        val tip = Offset(center.x + r * cos(end), center.y + r * sin(end))
        rotate(-90f + 290f + 90f, tip) {
            val h = stroke * 1.25f
            drawPath(
                Path().apply {
                    moveTo(tip.x + h, tip.y)
                    lineTo(tip.x - h * 0.4f, tip.y - h)
                    lineTo(tip.x - h * 0.4f, tip.y + h)
                    close()
                },
                Color.White,
            )
        }
    }
    AsyncImage(
        model = images.firstOrNull(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .offset(cx - cover / 2, cy - cover / 2)
            .size(cover)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f)),
    )
}

// ---- New music ----------------------------------------------------------------------------------------------

@Composable
private fun DeckArt(images: List<String>, style: MixStyle, stage: Stage, clock: State<Float>) {
    val badge = stringResource(Res.string.mix_badge_new)
    val measurer = rememberTextMeasurer()
    val card = stage.short * 0.52f
    val cx = stage.width / 2
    val cy = stage.height * 0.54f
    val count = images.size.coerceAtLeast(1)
    val period = 3.4f
    val step by rememberStep(clock, period)
    // Back to front: back right, back left, front. Every period the front sleeve slides off and the others move up.
    for (slot in 2 downTo 0) {
        Box(
            Modifier
                .offset(cx - card / 2, cy - card / 2)
                .size(card)
                .graphicsLayer {
                    val phase = clock.value / period
                    val frac = phase - floor(phase)
                    val f = smooth((frac - 0.62f) / 0.38f)
                    val rotations = floatArrayOf(0f, -10f, 10f)
                    val xs = floatArrayOf(0f, -0.22f, 0.22f)
                    val scales = floatArrayOf(1f, 0.86f, 0.86f)
                    if (slot == 0) {
                        translationX = f * card.toPx() * 1.2f
                        rotationZ = f * 14f
                        alpha = 1f - f
                    } else {
                        if (slot == 2) alpha = smooth(frac / 0.25f)
                        val to = slot - 1
                        rotationZ = lerp(rotations[slot], rotations[to], f)
                        translationX = lerp(xs[slot], xs[to], f) * card.toPx()
                        val s = lerp(scales[slot], scales[to], f)
                        scaleX = s
                        scaleY = s
                    }
                }
                .shadow(6.dp, Sleeve)
                .clip(Sleeve)
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            AsyncImage(
                model = images.getOrNull((step + slot) % count),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    // The sticker: a flat scalloped disc, turning slowly.
    val sticker = card * 0.50f
    Canvas(
        Modifier
            .offset(cx + card * 0.30f, cy - card * 0.78f)
            .size(sticker)
            .graphicsLayer { rotationZ = (clock.value * 18f) % 360f },
    ) {
        val c = center
        val outer = size.minDimension / 2
        val inner = outer * 0.86f
        val points = 14
        val path = Path()
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = (i * PI / points).toFloat()
            val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, style.accent)
        val text = measurer.measure(
            badge.uppercase(),
            TextStyle(fontSize = (sticker.value * 0.20f).sp, fontWeight = FontWeight.Black, color = Color(0xFF1A0B33)),
        )
        rotate(-16f, c) { drawText(text, topLeft = Offset(c.x - text.size.width / 2, c.y - text.size.height / 2)) }
    }
}

// ---- Your likes ---------------------------------------------------------------------------------------------

private val Heart = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w / 2, h * 0.92f)
    cubicTo(w * 0.10f, h * 0.62f, -w * 0.06f, h * 0.26f, w * 0.24f, h * 0.10f)
    cubicTo(w * 0.38f, h * 0.02f, w * 0.48f, h * 0.10f, w / 2, h * 0.22f)
    cubicTo(w * 0.52f, h * 0.10f, w * 0.62f, h * 0.02f, w * 0.76f, h * 0.10f)
    cubicTo(w * 1.06f, h * 0.26f, w * 0.90f, h * 0.62f, w / 2, h * 0.92f)
    close()
}

@Composable
private fun HeartArt(images: List<String>, stage: Stage, clock: State<Float>) {
    val size = stage.short * 0.70f
    val cx = stage.width / 2
    val cy = stage.height * 0.54f
    val step by rememberStep(clock, 3.2f)
    Box(
        Modifier
            .offset(cx - size / 2, cy - size / 2)
            .size(size)
            .graphicsLayer {
                // A heartbeat: two quick swells, then rest.
                val t = (clock.value % 1.6f) / 1.6f
                val beat = (if (t < 0.12f) sin(t / 0.12f * PI.toFloat()) else if (t in 0.2f..0.34f) sin((t - 0.2f) / 0.14f * PI.toFloat()) * 0.6f else 0f)
                val s = 1f + beat * 0.04f
                scaleX = s
                scaleY = s
            }
            .clip(Heart)
            .background(Color.White),
    ) {
        if (images.isNotEmpty()) {
            Crossfade(targetState = step % images.size, animationSpec = tween(900), label = "heart") { index ->
                AsyncImage(
                    model = images[index],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(size * 0.035f).clip(Heart),
                )
            }
        }
    }
}

// ---- Because you like ---------------------------------------------------------------------------------------

@Composable
private fun EchoArt(images: List<String>, stage: Stage, clock: State<Float>) {
    val cover = stage.short * 0.54f
    val cx = stage.width / 2
    val cy = stage.height * 0.54f
    // Two pale copies of the sleeve's frame behind it, drifting gently out and back: the songs it brings to mind.
    for (layer in 2 downTo 1) {
        Box(
            Modifier
                .offset(cx - cover / 2, cy - cover / 2)
                .size(cover)
                .graphicsLayer {
                    val drift = (sin(clock.value * 0.7f + layer) + 1f) / 2f
                    val d = cover.toPx() * (0.07f + 0.03f * drift) * layer
                    translationX = d
                    translationY = -d
                    alpha = if (layer == 1) 0.45f else 0.22f
                }
                .clip(Sleeve)
                .background(Color.White),
        )
    }
    AsyncImage(
        model = images.firstOrNull(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .offset(cx - cover / 2, cy - cover / 2)
            .size(cover)
            .shadow(6.dp, Sleeve)
            .clip(Sleeve)
            .background(Color.White.copy(alpha = 0.3f)),
    )
}

// ---- Genre mix ----------------------------------------------------------------------------------------------

@Composable
private fun GenreArt(images: List<String>, label: String, stage: Stage, clock: State<Float>) {
    val measurer = rememberTextMeasurer()
    val word = label.ifBlank { "MIX" }.uppercase()
    val rows = 3
    val rowHeight = stage.height / rows
    val type = TextStyle(fontSize = (rowHeight.value * 0.72f).sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
    val run = remember(word, rowHeight) { measurer.measure("$word  ", type) }
    // The genre in rows, the middle one solid, the others in outline, each running its own way.
    Canvas(Modifier.fillMaxSize()) {
        val runWidth = run.size.width.toFloat().coerceAtLeast(1f)
        for (row in 0 until rows) {
            val dir = if (row % 2 == 0) 1f else -1f
            val shift = ((clock.value * 18f * dir) % runWidth + runWidth) % runWidth
            val y = row * rowHeight.toPx() + (rowHeight.toPx() - run.size.height) / 2
            var x = -shift
            while (x < size.width) {
                if (row == 1) {
                    drawText(run, color = Color.White, topLeft = Offset(x, y))
                } else {
                    drawText(run, color = Color.White.copy(alpha = 0.5f), topLeft = Offset(x, y), drawStyle = Stroke(1.6.dp.toPx()))
                }
                x += runWidth
            }
        }
    }
    // Two of its artists over the type.
    val big = stage.short * 0.40f
    val small = stage.short * 0.30f
    listOf(
        Triple(0.32f, 0.46f, big),
        Triple(0.70f, 0.60f, small),
    ).zip(images.take(2)).forEach { (slot, url) ->
        val (fx, fy, d) = slot
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset(stage.width * fx - d / 2, stage.height * fy - d / 2)
                .size(d)
                .clip(CircleShape)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

// ---- Deep cuts ----------------------------------------------------------------------------------------------

@Composable
private fun ArtistArt(images: List<String>, stage: Stage, clock: State<Float>) {
    val face = stage.short * 0.66f
    val cx = stage.width / 2
    val cy = stage.height * 0.53f
    Box(
        Modifier
            .offset(cx - face / 2, cy - face / 2)
            .size(face)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        // Slowly closer, and back.
        AsyncImage(
            model = images.firstOrNull(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1.04f + (sin(clock.value * 0.35f) + 1f) * 0.04f
                    scaleX = s
                    scaleY = s
                },
        )
    }
    // A thin white ring a little way off the photo.
    Canvas(Modifier.offset(cx - face * 0.58f, cy - face * 0.58f).size(face * 1.16f)) {
        drawCircle(Color.White.copy(alpha = 0.7f), style = Stroke(2.dp.toPx()))
    }
}

// ---- Rediscover ---------------------------------------------------------------------------------------------

@Composable
private fun GridArt(images: List<String>, stage: Stage, clock: State<Float>) {
    val tile = stage.short * 0.34f
    val gap = tile * 0.10f
    val grid = tile * 2 + gap
    val left = (stage.width - grid) / 2
    val top = stage.height * 0.53f - grid / 2
    val step by rememberStep(clock, 2.2f)
    val count = images.size.coerceAtLeast(1)
    for (slot in 0 until 4) {
        // One tile changes each step, going round the grid.
        val turn = if (step >= slot) (step - slot) / 4 + 1 else 0
        Box(
            Modifier
                .offset(left + (tile + gap) * (slot % 2), top + (tile + gap) * (slot / 2))
                .size(tile)
                .clip(Sleeve)
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            if (images.isNotEmpty()) {
                Crossfade(targetState = (slot + turn * 4) % count, animationSpec = tween(800), label = "grid") { index ->
                    AsyncImage(model = images[index], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ---- One year ago -------------------------------------------------------------------------------------------

@Composable
private fun YearArt(images: List<String>, label: String, stage: Stage, clock: State<Float>) {
    val measurer = rememberTextMeasurer()
    val year = label.ifBlank { "" }
    val type = TextStyle(fontSize = (stage.short.value * 0.30f).sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, color = Color.White)
    val text = remember(year, stage.short) { measurer.measure(year, type) }
    Canvas(Modifier.fillMaxSize()) {
        drawText(text, topLeft = Offset((size.width - text.size.width) / 2, stage.height.toPx() * 0.10f))
    }
    // Three sleeves of that week, pinned under the year like photos.
    val card = stage.short * 0.32f
    val rotations = floatArrayOf(-8f, 3f, 9f)
    images.take(3).forEachIndexed { i, url ->
        Box(
            Modifier
                .offset(stage.width / 2 - card / 2 + card * 0.78f * (i - 1), stage.height * 0.66f - card / 2)
                .size(card)
                .graphicsLayer {
                    rotationZ = rotations[i] + sin(clock.value * 0.8f + i * 1.7f) * 1.5f
                    translationY = sin(clock.value * 0.6f + i) * 3.dp.toPx()
                }
                .shadow(5.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .padding(card * 0.05f),
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** 0 to 1 along a smooth step; anything outside is clamped. */
private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
