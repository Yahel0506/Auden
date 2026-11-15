package com.music.bitchord.ui.cover

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stickers, in five looks that each keep to their own palette and their own way of drawing:
 *
 * - Auden: the app's own — its mark, the DJ's ring, the blend, the star a song is liked with.
 * - Vintage: seventies print — mustard, rust, teal and cream, flat colour and a die-cut edge.
 * - Future: a heads-up display — hairlines and glow in cyan, lime and magenta on the void.
 * - Y2K: chrome and gel — polished metal, glossy bubbles, pastels with a shine.
 * - Graphic: Swiss print — black, off-white and one loud colour, bold and flat.
 *
 * Drawn in a square from -1 to 1 (a badge is the shorter rectangle inside it).
 */
internal object CoverStickers {
    // Vintage
    private const val MUSTARD = 0xFFE3A62F.toInt()
    private const val RUST = 0xFFC8512A.toInt()
    private const val TEAL = 0xFF2E6F6A.toInt()
    private const val CREAM = 0xFFF3E6C8.toInt()
    private const val BROWN = 0xFF3A2619.toInt()

    // Future
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val LIME = 0xFFC6FF3D.toInt()
    private const val MAGENTA = 0xFFFF2BD6.toInt()
    private const val VOID = 0xFF07080C.toInt()

    // Y2K
    private const val BABY = 0xFFA8D8FF.toInt()
    private const val BUBBLEGUM = 0xFFFF9AD5.toInt()
    private const val LILAC = 0xFFC9B6FF.toInt()
    private const val ACID = 0xFFD4FF3D.toInt()

    // Graphic
    private const val BLACK = 0xFF111111.toInt()
    private const val OFFWHITE = 0xFFF2EFE8.toInt()
    private const val KLEIN = 0xFF1F3BFF.toInt()
    private const val SIGNAL = 0xFFFF5B1F.toInt()

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Height as a share of width. */
    fun aspect(kind: StickerKind): Float = when (kind) {
        StickerKind.AUTOMIX, StickerKind.LOSSLESS, StickerKind.SYSTEM -> 0.42f
        StickerKind.BLEND -> 0.62f
        StickerKind.TAPE -> 0.7f
        StickerKind.ARCHES -> 0.6f
        StickerKind.SQUIGGLE -> 0.5f
        StickerKind.PULSE -> 0.62f
        StickerKind.BRACKETS -> 0.75f
        else -> 1f
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
    }

    /** Polished metal, banded light and shade. */
    private fun chrome(top: Float, bottom: Float) = linear(
        0f, top, 0f, bottom,
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFB9BEC7.toInt(), 0xFF5E6470.toInt(), 0xFFE8EBF0.toInt(), 0xFF8A909B.toInt()),
        floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f),
    )

    private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, mono: Boolean = false, weight: Int = 900, align: Paint.Align = Paint.Align.CENTER) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val base = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false) else Typeface.create(base, Typeface.BOLD)
            textSize = size
            this.color = color
            textAlign = align
            letterSpacing = 0.08f
        }
        canvas.drawText(s, x, y - (p.ascent() + p.descent()) / 2f, p)
    }

    /** The white edge a sticker is cut out with, and its shadow on the cover, round [outline]. */
    private fun dieCut(canvas: Canvas, outline: Path, edge: Float = 0.16f) {
        val p = stroke(WHITE, edge * 2f).apply {
            style = Paint.Style.FILL_AND_STROKE
            setShadowLayer(0.12f, 0f, 0.05f, 0x55000000)
        }
        canvas.drawPath(outline, p)
    }

    private fun circle(r: Float, cx: Float = 0f, cy: Float = 0f) = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

    private fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) = Path().apply { addRoundRect(RectF(l, t, r, b), radius, radius, Path.Direction.CW) }

    private fun star(points: Int, outer: Float, inner: Float, round: Float = 0f): Path = Path().apply {
        val n = points * 2
        val pts = Array(n) { i ->
            val r = if (i % 2 == 0) outer else inner
            val a = PI.toFloat() * i / points - PI.toFloat() / 2f
            floatArrayOf(r * cos(a), r * sin(a))
        }
        for (i in 0 until n) {
            val prev = pts[(i + n - 1) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val s = floatArrayOf(cur[0] + (prev[0] - cur[0]) * round, cur[1] + (prev[1] - cur[1]) * round)
            val e = floatArrayOf(cur[0] + (next[0] - cur[0]) * round, cur[1] + (next[1] - cur[1]) * round)
            if (i == 0) moveTo(s[0], s[1]) else lineTo(s[0], s[1])
            quadTo(cur[0], cur[1], e[0], e[1])
        }
        close()
    }

    /** A four-pointed glint with concave sides. */
    private fun glint(cx: Float, cy: Float, r: Float) = Path().apply {
        moveTo(cx, cy - r); quadTo(cx, cy, cx + r, cy); quadTo(cx, cy, cx, cy + r)
        quadTo(cx, cy, cx - r, cy); quadTo(cx, cy, cx, cy - r); close()
    }

    fun draw(canvas: Canvas, kind: StickerKind) {
        when (kind) {
            // ---- Auden ----
            StickerKind.LOGO -> {
                val tile = roundRect(-0.92f, -0.92f, 0.92f, 0.92f, 0.4f)
                dieCut(canvas, tile, 0.08f)
                canvas.drawPath(tile, linear(0f, -1f, 0f, 1f, intArrayOf(0xFF2A2B31.toInt(), CoverBrand.INK)))
                CoverBrand.drawMark(canvas, 0f, 0f, 1.45f, WHITE)
            }
            StickerKind.RING -> {
                canvas.drawPath(circle(0.98f), fill(CoverBrand.INK))
                CoverBrand.drawRing(
                    canvas, 0f, 0f, 1.3f, 72,
                    intArrayOf(CoverBrand.SKY, CoverBrand.VIOLET, CoverBrand.ROSE, CoverBrand.CORAL, CoverBrand.SUN, CoverBrand.MINT, CoverBrand.SKY),
                )
                CoverBrand.drawMark(canvas, 0f, 0f, 0.8f, WHITE)
            }
            StickerKind.BLEND -> {
                canvas.drawPath(circle(0.6f, -0.38f), fill(CoverBrand.CORAL))
                canvas.drawPath(circle(0.6f, 0.38f), fill(CoverBrand.SKY).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) })
            }
            StickerKind.STAR -> {
                val s = star(5, 0.95f, 0.45f, 0.25f)
                dieCut(canvas, s)
                canvas.drawPath(s, fill(CoverBrand.CORAL))
                canvas.drawPath(Path().apply { moveTo(-0.3f, -0.2f); quadTo(-0.12f, -0.58f, 0.02f, -0.64f) }, stroke(0x99FFFFFF.toInt(), 0.07f))
            }
            StickerKind.AUTOMIX -> {
                val pill = roundRect(-1f, -0.42f, 1f, 0.42f, 0.42f)
                canvas.drawPath(pill, fill(CoverBrand.INK))
                val cross = stroke(CoverBrand.CORAL, 0.07f)
                canvas.drawPath(Path().apply { moveTo(-0.82f, 0.18f); cubicTo(-0.65f, 0.18f, -0.6f, -0.18f, -0.43f, -0.18f) }, cross)
                canvas.drawPath(Path().apply { moveTo(-0.82f, -0.18f); cubicTo(-0.65f, -0.18f, -0.6f, 0.18f, -0.43f, 0.18f) }, cross.apply { color = CoverBrand.SKY })
                text(canvas, "AUTOMIX", 0.2f, 0f, 0.26f, WHITE)
            }
            StickerKind.LOSSLESS -> {
                val r = RectF(-1f, -0.42f, 1f, 0.42f)
                canvas.drawRoundRect(r, 0.14f, 0.14f, fill(CoverBrand.INK))
                canvas.drawRoundRect(RectF(r).apply { inset(0.035f, 0.035f) }, 0.12f, 0.12f, stroke(CoverBrand.SUN, 0.045f))
                canvas.drawPath(glint(-0.76f, 0f, 0.17f), fill(CoverBrand.SUN))
                text(canvas, "LOSSLESS", 0.08f, 0f, 0.27f, CoverBrand.SUN)
            }
            StickerKind.DJ -> {
                val tile = roundRect(-0.9f, -0.9f, 0.9f, 0.9f, 0.45f)
                dieCut(canvas, tile, 0.08f)
                canvas.drawPath(tile, fill(CoverBrand.INK))
                CoverBrand.drawRing(canvas, 0f, 0f, 1.08f, 60, intArrayOf(CoverBrand.CORAL, CoverBrand.ROSE, CoverBrand.VIOLET, CoverBrand.CORAL))
                text(canvas, "DJ", 0f, 0f, 0.5f, WHITE)
            }

            // ---- Vintage ----
            StickerKind.RETRO_SUN -> {
                dieCut(canvas, circle(0.95f))
                canvas.drawPath(circle(0.95f), fill(CREAM))
                // The sun sliced by bands that widen toward its foot, top mustard, bottom rust.
                canvas.save()
                canvas.clipPath(circle(0.72f, 0f, 0.05f))
                val bands = 7
                for (i in 0 until bands) {
                    val y0 = -0.67f + i * (1.44f / bands)
                    val gap = 0.015f + i * 0.022f
                    canvas.drawRect(-1f, y0 + gap, 1f, y0 + 1.44f / bands, fill(CoverPalette.mix(MUSTARD, RUST, i / (bands - 1f))))
                }
                canvas.restore()
            }
            StickerKind.ARCHES -> {
                val colors = intArrayOf(BROWN, RUST, MUSTARD, TEAL)
                val outline = Path().apply { addArc(RectF(-1f, -0.6f, 1f, 1.4f), 180f, 180f); lineTo(1f, 0.4f); lineTo(-1f, 0.4f); close() }
                dieCut(canvas, outline, 0.1f)
                for (i in colors.indices) {
                    val r = 1f - i * 0.22f
                    canvas.drawArc(RectF(-r, 0.4f - r, r, 0.4f + r), 180f, 180f, true, fill(colors[i]))
                }
                canvas.drawArc(RectF(-0.12f, 0.28f, 0.12f, 0.52f), 180f, 180f, true, fill(CREAM))
            }
            StickerKind.HIFI -> {
                val scallop = Path().apply {
                    val n = 22
                    for (i in 0..n * 4) {
                        val a = 2f * PI.toFloat() * i / (n * 4)
                        val r = 0.9f + 0.06f * cos(a * n)
                        if (i == 0) moveTo(r * cos(a), r * sin(a)) else lineTo(r * cos(a), r * sin(a))
                    }
                    close()
                }
                dieCut(canvas, scallop, 0.08f)
                canvas.drawPath(scallop, fill(CREAM))
                canvas.drawPath(scallop, stroke(BROWN, 0.05f))
                canvas.drawCircle(0f, 0f, 0.7f, stroke(BROWN, 0.025f))
                text(canvas, "HI-FI", 0f, -0.06f, 0.46f, RUST)
                text(canvas, "STEREO SOUND", 0f, 0.36f, 0.13f, TEAL, weight = 800)
                text(canvas, "★ ★ ★", 0f, -0.48f, 0.13f, MUSTARD)
            }
            StickerKind.RECORD -> {
                dieCut(canvas, circle(0.97f), 0.06f)
                canvas.drawPath(circle(0.97f), fill(BROWN))
                for (r in floatArrayOf(0.88f, 0.8f, 0.72f, 0.64f, 0.56f)) canvas.drawCircle(0f, 0f, r, stroke(0x22000000, 0.03f))
                canvas.drawPath(circle(0.38f), fill(MUSTARD))
                canvas.drawRect(-0.38f, -0.06f, 0.38f, 0.06f, fill(RUST).apply { alpha = 220 })
                canvas.drawPath(circle(0.05f), fill(BROWN))
                canvas.drawArc(RectF(-0.85f, -0.85f, 0.85f, 0.85f), 200f, 50f, false, stroke(0x40FFFFFF, 0.05f))
            }
            StickerKind.TAPE -> {
                val body = roundRect(-1f, -0.7f, 1f, 0.7f, 0.12f)
                dieCut(canvas, body, 0.08f)
                canvas.drawPath(body, fill(CREAM))
                canvas.drawRoundRect(RectF(-0.9f, -0.6f, 0.9f, -0.18f), 0.06f, 0.06f, fill(RUST))
                canvas.drawRect(-0.9f, -0.18f, 0.9f, -0.1f, fill(MUSTARD))
                text(canvas, "C-60", 0.72f, -0.39f, 0.16f, CREAM, align = Paint.Align.RIGHT)
                canvas.drawRoundRect(RectF(-0.58f, 0.02f, 0.58f, 0.5f), 0.22f, 0.22f, fill(TEAL))
                canvas.drawPath(circle(0.14f, -0.3f, 0.26f), fill(CREAM))
                canvas.drawPath(circle(0.14f, 0.3f, 0.26f), fill(CREAM))
            }
            StickerKind.ATOMIC -> {
                val arms = 8
                for (i in 0 until arms) {
                    val a = 2f * PI.toFloat() * i / arms + 0.2f
                    val r = if (i % 2 == 0) 0.95f else 0.68f
                    canvas.drawLine(0f, 0f, r * cos(a), r * sin(a), stroke(BROWN, 0.06f))
                    canvas.drawCircle(r * cos(a), r * sin(a), if (i % 2 == 0) 0.12f else 0.08f, fill(if (i % 2 == 0) RUST else TEAL))
                }
                canvas.drawPath(circle(0.18f), fill(MUSTARD))
            }

            // ---- Future ----
            StickerKind.HUD -> {
                val line = stroke(CYAN, 0.025f)
                canvas.drawCircle(0f, 0f, 0.92f, line)
                canvas.drawArc(RectF(-0.78f, -0.78f, 0.78f, 0.78f), -40f, 110f, false, stroke(CYAN, 0.07f))
                canvas.drawArc(RectF(-0.78f, -0.78f, 0.78f, 0.78f), 150f, 60f, false, stroke(MAGENTA, 0.07f))
                canvas.drawCircle(0f, 0f, 0.55f, stroke(0x8800E5FF.toInt(), 0.02f))
                for (i in 0 until 48) {
                    val a = 2f * PI.toFloat() * i / 48
                    val inner = if (i % 6 == 0) 0.82f else 0.87f
                    canvas.drawLine(inner * cos(a), inner * sin(a), 0.92f * cos(a), 0.92f * sin(a), stroke(CYAN, 0.018f))
                }
                canvas.drawLine(-0.3f, 0f, -0.08f, 0f, line)
                canvas.drawLine(0.08f, 0f, 0.3f, 0f, line)
                canvas.drawLine(0f, -0.3f, 0f, -0.08f, line)
                canvas.drawLine(0f, 0.08f, 0f, 0.3f, line)
                text(canvas, "LOCK", 0f, 0.7f, 0.1f, CYAN, mono = true, weight = 700)
            }
            StickerKind.WIREFRAME -> {
                val glow = stroke(CYAN, 0.025f).apply { setShadowLayer(0.06f, 0f, 0f, CYAN) }
                canvas.drawCircle(0f, 0f, 0.9f, glow)
                for (k in 1..4) {
                    val ry = 0.9f * k / 5f
                    canvas.drawOval(RectF(-0.9f, -ry, 0.9f, ry), glow.apply { color = CoverPalette.mix(CYAN, MAGENTA, k / 5f) })
                    val rx = 0.9f * k / 5f
                    canvas.drawOval(RectF(-rx, -0.9f, rx, 0.9f), glow)
                }
            }
            StickerKind.PULSE -> {
                val r = RectF(-1f, -0.62f, 1f, 0.62f)
                canvas.drawRoundRect(r, 0.12f, 0.12f, fill(VOID))
                val grid = stroke(0x2200E5FF, 0.012f)
                var x = -0.9f
                while (x < 1f) { canvas.drawLine(x, -0.6f, x, 0.6f, grid); x += 0.2f }
                var y = -0.5f
                while (y < 0.6f) { canvas.drawLine(-1f, y, 1f, y, grid); y += 0.2f }
                val path = Path().apply {
                    moveTo(-0.9f, 0.1f); lineTo(-0.45f, 0.1f); lineTo(-0.32f, -0.15f); lineTo(-0.18f, 0.42f)
                    lineTo(-0.02f, -0.48f); lineTo(0.12f, 0.24f); lineTo(0.24f, 0.1f); lineTo(0.9f, 0.1f)
                }
                canvas.drawPath(path, stroke(LIME, 0.05f).apply { setShadowLayer(0.08f, 0f, 0f, LIME) })
                text(canvas, "120 BPM", 0.86f, -0.46f, 0.11f, LIME, mono = true, weight = 700, align = Paint.Align.RIGHT)
            }
            StickerKind.GRID_FLOOR -> {
                val clip = roundRect(-0.95f, -0.95f, 0.95f, 0.95f, 0.18f)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawPaint(linear(0f, -1f, 0f, 0f, intArrayOf(0xFF140326.toInt(), 0xFF3A0A4A.toInt())))
                // A setting sun on the horizon, cut by scan lines.
                canvas.save()
                canvas.clipRect(-1f, -1f, 1f, 0.02f)
                canvas.drawCircle(0f, 0.02f, 0.5f, linear(0f, -0.5f, 0f, 0f, intArrayOf(0xFFFFD34D.toInt(), MAGENTA)))
                for (i in 0 until 5) canvas.drawRect(-0.6f, -0.02f - i * 0.09f, 0.6f, -0.0f - i * 0.09f, fill(0xFF3A0A4A.toInt()))
                canvas.restore()
                canvas.drawRect(-1f, 0.02f, 1f, 1f, fill(VOID))
                val grid = stroke(MAGENTA, 0.022f).apply { setShadowLayer(0.05f, 0f, 0f, MAGENTA) }
                for (i in -6..6) canvas.drawLine(i * 0.08f, 0.02f, i * 0.5f, 1f, grid)
                var y = 0.06f
                var step = 0.04f
                while (y < 1f) { canvas.drawLine(-1f, y, 1f, y, grid); y += step; step *= 1.45f }
                canvas.restore()
            }
            StickerKind.SYSTEM -> {
                canvas.drawRect(-1f, -0.42f, 1f, 0.42f, fill(VOID))
                val c = stroke(LIME, 0.04f).apply { strokeCap = Paint.Cap.BUTT }
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    canvas.drawLine(sx * 0.98f, sy * 0.4f, sx * 0.98f - sx * 0.16f, sy * 0.4f, c)
                    canvas.drawLine(sx * 0.98f, sy * 0.4f, sx * 0.98f, sy * 0.4f - sy * 0.16f, c)
                }
                text(canvas, "SYS//AUDEN", 0f, 0f, 0.22f, LIME, mono = true, weight = 700)
            }
            StickerKind.BRACKETS -> {
                val c = stroke(WHITE, 0.05f).apply { strokeCap = Paint.Cap.SQUARE }
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    canvas.drawLine(sx * 0.95f, sy * 0.7f, sx * 0.65f, sy * 0.7f, c)
                    canvas.drawLine(sx * 0.95f, sy * 0.7f, sx * 0.95f, sy * 0.42f, c)
                }
                canvas.drawCircle(-0.62f, -0.42f, 0.07f, fill(0xFFFF2D2D.toInt()))
                text(canvas, "REC", -0.48f, -0.42f, 0.14f, WHITE, mono = true, weight = 700, align = Paint.Align.LEFT)
                text(canvas, "00:03:21", 0.72f, 0.46f, 0.11f, WHITE, mono = true, weight = 700, align = Paint.Align.RIGHT)
            }

            // ---- Y2K ----
            StickerKind.CHROME_STAR -> {
                val s = star(4, 1f, 0.3f, 0.12f)
                canvas.drawPath(s, chrome(-1f, 1f))
                canvas.drawPath(s, stroke(0x66FFFFFF, 0.03f))
                canvas.drawPath(glint(0.55f, -0.6f, 0.18f), fill(WHITE))
            }
            StickerKind.BUBBLES -> {
                fun bubble(x: Float, y: Float, r: Float, tint: Int) {
                    canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f, intArrayOf(0x22FFFFFF, (tint and 0xFFFFFF) or 0x88000000.toInt()), null, Shader.TileMode.CLAMP)
                    })
                    canvas.drawCircle(x, y, r, stroke(0xAAFFFFFF.toInt(), r * 0.05f))
                    canvas.drawOval(RectF(x - r * 0.55f, y - r * 0.7f, x - r * 0.05f, y - r * 0.35f), fill(0xCCFFFFFF.toInt()))
                }
                bubble(-0.3f, 0.15f, 0.62f, BABY)
                bubble(0.48f, -0.42f, 0.42f, BUBBLEGUM)
                bubble(0.55f, 0.55f, 0.3f, LILAC)
            }
            StickerKind.BUTTERFLY -> {
                fun wing(sx: Float) {
                    val upper = Path().apply { moveTo(0f, -0.05f); cubicTo(sx * 0.4f, -1.0f, sx * 1.05f, -0.75f, sx * 0.85f, -0.15f); cubicTo(sx * 0.7f, 0.05f, sx * 0.25f, 0.05f, 0f, -0.05f); close() }
                    val lower = Path().apply { moveTo(0f, 0.02f); cubicTo(sx * 0.6f, 0.05f, sx * 0.8f, 0.7f, sx * 0.35f, 0.75f); cubicTo(sx * 0.1f, 0.78f, 0f, 0.4f, 0f, 0.02f); close() }
                    val paint = linear(0f, -0.9f, sx * 0.9f, 0.7f, intArrayOf(LILAC, BABY, BUBBLEGUM))
                    canvas.drawPath(upper, paint)
                    canvas.drawPath(lower, paint)
                    canvas.drawPath(upper, stroke(0xAAFFFFFF.toInt(), 0.03f))
                    canvas.drawPath(lower, stroke(0xAAFFFFFF.toInt(), 0.03f))
                    canvas.drawCircle(sx * 0.55f, -0.45f, 0.08f, fill(0xCCFFFFFF.toInt()))
                }
                wing(-1f)
                wing(1f)
                canvas.drawRoundRect(RectF(-0.05f, -0.4f, 0.05f, 0.6f), 0.05f, 0.05f, fill(0xFF3B3350.toInt()))
                canvas.drawLine(0f, -0.38f, -0.18f, -0.7f, stroke(0xFF3B3350.toInt(), 0.025f))
                canvas.drawLine(0f, -0.38f, 0.18f, -0.7f, stroke(0xFF3B3350.toInt(), 0.025f))
            }
            StickerKind.FLIP_PHONE -> {
                val body = roundRect(-0.5f, -0.95f, 0.5f, 0.95f, 0.18f)
                dieCut(canvas, body, 0.08f)
                canvas.drawPath(body, chrome(-0.95f, 0.95f))
                canvas.drawRoundRect(RectF(-0.38f, -0.8f, 0.38f, -0.12f), 0.06f, 0.06f, linear(0f, -0.8f, 0f, -0.12f, intArrayOf(0xFF7FD1FF.toInt(), 0xFF2E6CE6.toInt())))
                text(canvas, "♪ AUDEN", 0f, -0.46f, 0.12f, WHITE, weight = 800)
                for (row in 0 until 4) for (col in 0 until 3) {
                    canvas.drawRoundRect(RectF(-0.3f + col * 0.22f, 0.02f + row * 0.21f, -0.14f + col * 0.22f, 0.15f + row * 0.21f), 0.04f, 0.04f, fill(0xFF4A505B.toInt()))
                }
            }
            StickerKind.SMILEY -> {
                dieCut(canvas, circle(0.92f))
                canvas.drawPath(circle(0.92f), fill(ACID))
                canvas.drawOval(RectF(-0.4f, -0.45f, -0.2f, -0.05f), fill(BLACK))
                canvas.drawOval(RectF(0.2f, -0.45f, 0.4f, -0.05f), fill(BLACK))
                canvas.drawArc(RectF(-0.55f, -0.3f, 0.55f, 0.6f), 25f, 130f, false, stroke(BLACK, 0.1f))
            }

            // ---- Graphic ----
            StickerKind.ASTERISK -> {
                val arm = stroke(BLACK, 0.36f)
                for (i in 0 until 3) {
                    val a = PI.toFloat() * i / 3f + PI.toFloat() / 2f
                    canvas.drawLine(-0.78f * cos(a), -0.78f * sin(a), 0.78f * cos(a), 0.78f * sin(a), arm)
                }
            }
            StickerKind.SQUIGGLE -> {
                val path = Path().apply {
                    moveTo(-0.9f, 0f)
                    for (i in 0 until 4) {
                        val x = -0.9f + i * 0.45f
                        quadTo(x + 0.11f, if (i % 2 == 0) -0.5f else 0.5f, x + 0.225f, 0f)
                        quadTo(x + 0.34f, if (i % 2 == 0) 0.5f else -0.5f, x + 0.45f, 0f)
                    }
                }
                canvas.drawPath(path, stroke(SIGNAL, 0.2f))
            }
            StickerKind.HALVES -> {
                canvas.drawArc(RectF(-0.85f, -0.95f, 0.85f, 0.75f), 180f, 180f, true, fill(KLEIN))
                canvas.drawArc(RectF(-0.85f, -0.75f, 0.85f, 0.95f), 0f, 180f, true, fill(SIGNAL))
            }
            StickerKind.FLOWER -> {
                for (i in 0 until 6) {
                    val a = 2f * PI.toFloat() * i / 6f
                    canvas.drawCircle(0.48f * cos(a), 0.48f * sin(a), 0.38f, fill(SIGNAL))
                }
                canvas.drawCircle(0f, 0f, 0.32f, fill(BLACK))
            }
            StickerKind.ARROW -> {
                val shaft = Path().apply { moveTo(-0.8f, 0.6f); cubicTo(-0.6f, -0.3f, 0.1f, -0.55f, 0.6f, -0.45f) }
                canvas.drawPath(shaft, stroke(BLACK, 0.2f))
                canvas.drawPath(Path().apply { moveTo(0.35f, -0.82f); lineTo(0.92f, -0.42f); lineTo(0.38f, -0.06f); close() }, fill(BLACK).apply { pathEffect = android.graphics.CornerPathEffect(0.06f) })
            }
            StickerKind.BOLT -> {
                val bolt = Path().apply {
                    moveTo(0.22f, -1f); lineTo(-0.58f, 0.12f); lineTo(-0.05f, 0.12f)
                    lineTo(-0.28f, 1f); lineTo(0.62f, -0.18f); lineTo(0.08f, -0.18f); close()
                }
                canvas.drawPath(bolt, fill(SIGNAL))
                canvas.drawPath(bolt, stroke(BLACK, 0.07f))
            }
        }
    }
}
