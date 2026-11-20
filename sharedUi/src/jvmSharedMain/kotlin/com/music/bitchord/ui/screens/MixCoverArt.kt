package com.music.bitchord.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
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
 * The colours one of the generated lists wears: its cover's backdrop, the accent its marks are drawn in, the
 * eyebrow on its card, and — through [top] and [accent] — the tint of the page it opens, as every list's page is
 * tinted by its cover.
 */
data class MixStyle(val top: Color, val bottom: Color, val accent: Color, val eyebrow: Color)

fun TopPickKind.mixStyle(): MixStyle = when (this) {
    // Embers: a record spinning on a warm dark.
    TopPickKind.IN_LOOP -> MixStyle(Color(0xFF1C0904), Color(0xFF9A2E0A), Color(0xFFFF7A2F), Color(0xFFFFD2B5))
    // Ink and neon teal: a poster.
    TopPickKind.GENRE_MIX -> MixStyle(Color(0xFF031A26), Color(0xFF0B5C70), Color(0xFF5EEAD4), Color(0xFFB9FFF2))
    // Midnight violet with an electric lime sticker.
    TopPickKind.NEW_FOR_YOU -> MixStyle(Color(0xFF0E0524), Color(0xFF43178C), Color(0xFFD7FF5E), Color(0xFFE6D6FF))
    // Deep rose: a song and its echoes.
    else -> MixStyle(Color(0xFF22040F), Color(0xFFA3154E), Color(0xFFFF8FBF), Color(0xFFFFD1E2))
}

/** The small line above a generated list's title: its colour, matched to its cover. */
fun TopPickKind.mixEyebrow(): Color = mixStyle().eyebrow

/** The kind of a generated list's page, read off its id (`local:mix:loop` …); null for any other page. */
fun mixKindOf(pageId: String?): TopPickKind? = when (pageId) {
    "local:mix:loop" -> TopPickKind.IN_LOOP
    "local:mix:genre" -> TopPickKind.GENRE_MIX
    "local:mix:new" -> TopPickKind.NEW_FOR_YOU
    "local:mix:because" -> TopPickKind.BECAUSE_YOU_LIKE
    else -> null
}

/**
 * The cover of one of the lists Auden makes each day, each with an idea of its own and always moving:
 *
 * - **In loop**: a record spinning with the most repeated song on its label, a loop arrow orbiting it.
 * - **Genre mix**: a poster — the genre in heavy type running in rows, the artists in it in duotone.
 * - **New music**: a fanned deck of new sleeves dealing itself, a "new" sticker turning on top.
 * - **Because you like**: the song, its echoes widening out, songs like it circling round.
 *
 * The same cover on the big card on Home and at the head of the list's page, so opening it changes nothing of what it
 * looks like. With [captionSpace] (the card) it keeps to the upper part and darkens the foot for the caption; without
 * it (the page) it is centred on the whole square. [label] is what the cover may write: the genre's name.
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
    Box(modifier.background(Brush.verticalGradient(listOf(style.top, style.bottom)))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The stage the art keeps to: the upper part of a card, the whole square of a page.
            val stage = Stage(
                width = maxWidth,
                height = if (captionSpace) maxHeight * 0.64f else maxHeight,
            )
            when (kind) {
                TopPickKind.IN_LOOP -> LoopArt(images, style, stage, clock)
                TopPickKind.GENRE_MIX -> GenreArt(images, label, style, stage, clock)
                TopPickKind.NEW_FOR_YOU -> FreshArt(images, style, stage, clock)
                else -> EchoArt(images, style, stage, clock)
            }
        }
        if (captionSpace) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(0.52f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f))),
            )
        }
    }
}

private class Stage(val width: Dp, val height: Dp)

/** A picture in grey with the colour [tint] laid over it: one cover in the list's own two tones. */
private fun Modifier.duotone(tint: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(tint, blendMode = BlendMode.Color)
    }

private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

// ---- In loop: the record ------------------------------------------------------------------------------------

@Composable
private fun LoopArt(images: List<String>, style: MixStyle, stage: Stage, clock: State<Float>) {
    val disc = minOf(stage.width * 0.66f, stage.height * 0.80f)
    val cx = stage.width / 2
    val cy = stage.height * 0.52f
    // A warm glow under the record.
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            Brush.radialGradient(listOf(style.accent.copy(alpha = 0.55f), Color.Transparent), Offset(cx.toPx(), cy.toPx()), disc.toPx() * 0.9f),
            radius = disc.toPx() * 0.9f,
            center = Offset(cx.toPx(), cy.toPx()),
        )
    }
    // The orbiting loop: an arc that brightens into an arrowhead, turning the other way.
    val orbit = disc * 1.26f
    Canvas(
        Modifier
            .offset(cx - orbit / 2, cy - orbit / 2)
            .size(orbit)
            .graphicsLayer { rotationZ = -(clock.value * 34f) % 360f },
    ) {
        val stroke = size.width * 0.028f
        val inset = stroke * 1.6f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            brush = Brush.sweepGradient(listOf(Color.Transparent, style.accent.copy(alpha = 0.35f), style.accent), center),
            startAngle = 0f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        val r = arcSize.width / 2
        val a = 300f * (PI / 180f).toFloat()
        val tip = Offset(center.x + r * cos(a), center.y + r * sin(a))
        // The arrowhead, pointing along the arc.
        rotate(300f + 90f, tip) {
            val h = stroke * 2.8f
            drawPath(
                Path().apply {
                    moveTo(tip.x + h * 0.9f, tip.y)
                    lineTo(tip.x - h * 0.55f, tip.y - h * 0.75f)
                    lineTo(tip.x - h * 0.55f, tip.y + h * 0.75f)
                    close()
                },
                style.accent,
            )
        }
    }
    // The record, turning.
    Box(
        Modifier
            .offset(cx - disc / 2, cy - disc / 2)
            .size(disc)
            .shadow(18.dp, CircleShape)
            .graphicsLayer { rotationZ = (clock.value * 42f) % 360f },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF0C0B0D))
            for (i in 0..22) {
                drawCircle(
                    Color.White.copy(alpha = if (i % 4 == 0) 0.09f else 0.04f),
                    radius = r * (0.40f + i * 0.026f),
                    style = Stroke(0.8.dp.toPx()),
                )
            }
            // The light on the vinyl, two soft wedges.
            drawCircle(
                Brush.sweepGradient(
                    listOf(
                        Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Transparent,
                        Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent,
                    ),
                ),
                radius = r,
            )
        }
        AsyncImage(
            model = images.firstOrNull(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.Center)
                .size(disc * 0.40f)
                .clip(CircleShape)
                .background(style.accent),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .size(disc * 0.045f)
                .clip(CircleShape)
                .background(Color(0xFF0C0B0D)),
        )
    }
}

// ---- Genre mix: the poster ----------------------------------------------------------------------------------

@Composable
private fun GenreArt(images: List<String>, label: String, style: MixStyle, stage: Stage, clock: State<Float>) {
    val measurer = rememberTextMeasurer()
    val word = label.ifBlank { "MIX" }.uppercase()
    val rows = 5
    val rowHeight = stage.height / rows
    val type = TextStyle(
        fontSize = (rowHeight.value * 0.82f).sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
    )
    val run = remember(word, type) { measurer.measure("$word · ", type) }
    // The genre, running in rows, each its own way: the middle one solid in the accent, the rest drawn in outline.
    Canvas(Modifier.fillMaxSize()) {
        val runWidth = run.size.width.toFloat().coerceAtLeast(1f)
        for (row in 0 until rows) {
            val dir = if (row % 2 == 0) 1f else -1f
            val speed = 22f + row * 6f
            val shift = ((clock.value * speed * dir) % runWidth + runWidth) % runWidth
            val y = row * rowHeight.toPx() + (rowHeight.toPx() - run.size.height) / 2
            var x = -shift
            while (x < size.width) {
                if (row == rows / 2) {
                    drawText(run, color = style.accent, topLeft = Offset(x, y))
                } else {
                    drawText(run, color = Color.White.copy(alpha = 0.22f), topLeft = Offset(x, y), drawStyle = Stroke(1.2.dp.toPx()))
                }
                x += runWidth
            }
        }
    }
    // The artists, in duotone, floating over it.
    val slots = listOf(
        Triple(0.30f, 0.40f, 0.42f),
        Triple(0.72f, 0.30f, 0.30f),
        Triple(0.70f, 0.70f, 0.26f),
    )
    slots.zip(images.take(3)).forEachIndexed { i, (slot, url) ->
        val (fx, fy, fd) = slot
        val d = minOf(stage.width, stage.height) * fd * 1.15f
        Box(
            Modifier
                .offset(stage.width * fx - d / 2, stage.height * fy - d / 2)
                .size(d)
                .graphicsLayer { translationY = sin(clock.value * 0.9f + i * 2.1f) * 5.dp.toPx() }
                .shadow(14.dp, CircleShape)
                .clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = Greyscale,
                modifier = Modifier.fillMaxSize().duotone(style.accent),
            )
        }
    }
}

// ---- New music: the deck ------------------------------------------------------------------------------------

@Composable
private fun FreshArt(images: List<String>, style: MixStyle, stage: Stage, clock: State<Float>) {
    val badge = stringResource(Res.string.mix_badge_new)
    val measurer = rememberTextMeasurer()
    // Soft rays turning slowly behind the deck.
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, stage.height.toPx() * 0.5f)
        rotate(clock.value * 7f, c) {
            drawCircle(
                Brush.sweepGradient(
                    listOf(
                        Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent,
                        Color.White.copy(alpha = 0.06f), Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent,
                    ),
                    c,
                ),
                radius = size.maxDimension,
                center = c,
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(style.bottom.copy(alpha = 0.9f), Color.Transparent), c, stage.width.toPx() * 0.55f),
            radius = stage.width.toPx() * 0.55f,
            center = c,
        )
    }
    val card = minOf(stage.width * 0.50f, stage.height * 0.66f)
    val cx = stage.width / 2
    val cy = stage.height * 0.52f
    val count = images.size.coerceAtLeast(1)
    // Every few seconds the front sleeve slides off and goes to the back of the deck.
    val period = 3.4f
    Box(Modifier.fillMaxSize()) {
        // Back to front: slot 2 (back right), slot 1 (back left), slot 0 (front).
        for (slot in 2 downTo 0) {
            Box(
                Modifier
                    .offset(cx - card / 2, cy - card / 2)
                    .size(card)
                    .graphicsLayer {
                        val phase = clock.value / period
                        val f = smooth(((phase - floor(phase)) - 0.62f) / 0.38f)
                        val rotations = floatArrayOf(0f, -11f, 11f)
                        val xs = floatArrayOf(0f, -0.20f, 0.20f)
                        val scales = floatArrayOf(1f, 0.84f, 0.84f)
                        // Where this card is going: the front goes out to the right, the others move one place up.
                        if (slot == 0) {
                            translationX = f * card.toPx() * 1.1f
                            rotationZ = f * 16f
                            alpha = 1f - f
                        } else {
                            // The card that joins the back of the deck fades in rather than appearing.
                            if (slot == 2) alpha = smooth((phase - floor(phase)) / 0.25f)
                            val to = slot - 1
                            rotationZ = lerp(rotations[slot], rotations[to], f)
                            translationX = lerp(xs[slot], xs[to], f) * card.toPx()
                            val s = lerp(scales[slot], scales[to], f)
                            scaleX = s
                            scaleY = s
                        }
                    }
                    .shadow(16.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(style.bottom),
            ) {
                DeckSleeve(images, clock, period, slot, count)
            }
        }
    }
    // The sticker, turning on top.
    val sticker = card * 0.56f
    Canvas(
        Modifier
            .offset(cx + card * 0.30f, cy - card * 0.74f)
            .size(sticker)
            .graphicsLayer { rotationZ = (clock.value * 22f) % 360f },
    ) {
        val c = center
        val outer = size.minDimension / 2
        val inner = outer * 0.84f
        val points = 16
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
            TextStyle(fontSize = (sticker.value * 0.19f).sp, fontWeight = FontWeight.Black, color = Color(0xFF14052E)),
        )
        rotate(-18f, c) {
            drawText(text, topLeft = Offset(c.x - text.size.width / 2, c.y - text.size.height / 2))
        }
    }
}

/** The sleeve in [slot] of the deck: each slot moves one place a period, so its sleeve is the next one round. */
@Composable
private fun DeckSleeve(images: List<String>, clock: State<Float>, period: Float, slot: Int, count: Int) {
    // Recomposed once a period, when the deck moves on, and not on every frame the clock ticks.
    val step by remember(period) { androidx.compose.runtime.derivedStateOf { floor(clock.value / period).toInt() } }
    AsyncImage(
        model = images.getOrNull((step + slot) % count),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

// ---- Because you like: the echoes ---------------------------------------------------------------------------

@Composable
private fun EchoArt(images: List<String>, style: MixStyle, stage: Stage, clock: State<Float>) {
    val cover = minOf(stage.width * 0.46f, stage.height * 0.60f)
    val cx = stage.width / 2
    val cy = stage.height * 0.52f
    // The song's colour spread soft behind it.
    AsyncImage(
        model = images.firstOrNull(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = Greyscale,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.28f }
            .duotone(style.accent),
    )
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, style.top.copy(alpha = 0.85f)))))
    // The echoes, widening out of the cover and fading as they go.
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(cx.toPx(), cy.toPx())
        val base = cover.toPx()
        for (i in 0 until 3) {
            val p = ((clock.value / 2.6f) + i / 3f) % 1f
            val s = base * (1f + p * 0.75f)
            val corner = s * 0.12f
            drawRoundRect(
                color = style.accent.copy(alpha = (1f - p) * 0.7f),
                topLeft = Offset(c.x - s / 2, c.y - s / 2),
                size = Size(s, s),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke((2.5f - p * 1.5f).dp.toPx()),
            )
        }
    }
    // Songs like it, circling.
    val satellite = cover * 0.32f
    images.drop(1).take(3).forEachIndexed { i, url ->
        Box(
            Modifier
                .offset(cx - satellite / 2, cy - satellite / 2)
                .size(satellite)
                .graphicsLayer {
                    val a = ((clock.value * 18f) + i * 120f) * (PI / 180f).toFloat()
                    val r = cover.toPx() * 0.86f
                    translationX = r * cos(a)
                    translationY = r * sin(a) * 0.82f
                }
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
    // The song itself.
    Box(
        Modifier
            .offset(cx - cover / 2, cy - cover / 2)
            .size(cover)
            .graphicsLayer {
                val breath = 1f + sin(clock.value * 1.4f) * 0.015f
                scaleX = breath
                scaleY = breath
            }
            .shadow(22.dp, RoundedCornerShape(cover * 0.12f))
            .clip(RoundedCornerShape(cover * 0.12f))
            .background(style.bottom),
    ) {
        AsyncImage(model = images.firstOrNull(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** 0 to 1 along a smooth step; anything outside is clamped. */
private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
