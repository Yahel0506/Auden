package com.music.bitchord.ui.cover

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import java.util.Calendar
import kotlin.math.max

/**
 * The plates a playlist's name can be set on. Every one is its own small design — a ticket with a stub, a tape's
 * paper label, a neon sign, an on-air light — and carries Auden with it: the mark, the ring's colours, a second
 * line with the year or the song count. All drawn from the title and the size of the cover, so they fit any name.
 */
internal object CoverLabels {
    private class Box(val w: Float, val h: Float, val u: Float, val title: CharSequence, val tw: Float)

    private fun face(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private fun text(px: Float, weight: Int = 800, color: Int = 0xFF000000.toInt(), spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face(weight)
        textSize = px
        this.color = color
        letterSpacing = spacing
    }

    private fun fitted(text: String, paint: TextPaint, max: Float): CharSequence =
        TextUtils.ellipsize(text.ifBlank { " " }, paint, max, TextUtils.TruncateAt.END)

    private val year: String get() = Calendar.getInstance().get(Calendar.YEAR).toString()

    /** How wide a small line of [s] is, at [size] units, as [small] draws it. */
    private fun sw(s: String, u: Float, size: Float, weight: Int = 700) = text(u * size, weight, 0, 0.18f).measureText(s)

    private fun box(l: LabelLayer, side: Float): Box {
        val u = side * 0.06f
        val titlePx = when (l.style) {
            LabelStyle.TICKET -> 1.25f
            LabelStyle.CASSETTE -> 1.3f
            else -> 1.2f
        } * u
        val paint = text(titlePx)
        val title = fitted(l.text, paint, side * 0.54f)
        val tw = paint.measureText(title, 0, title.length)
        // Every plate is as wide as the widest thing on it, small lines included, so nothing runs off its edge.
        val (w, h) = when (l.style) {
            LabelStyle.GLASS -> (0.9f + 2.2f + 0.7f) * u + tw + 1.1f * u to 2.6f * u
            LabelStyle.TICKET -> 2.2f * u + maxOf(tw, sw("AUDEN  ·  PLAYLIST", u, 0.42f), 4.6f * u) + 3.4f * u to 3.9f * u
            LabelStyle.SLEEVE -> 4.3f * u + maxOf(tw, sw("AUDEN  ·  $year", u, 0.42f), 4f * u) + 1.1f * u to 3.5f * u
            LabelStyle.CASSETTE -> maxOf(7.6f * u, tw + 2.2f * u, sw("SIDE A", u, 0.5f) + sw("AUDEN  C-60", u, 0.5f) + 2.6f * u) to 4.8f * u
            LabelStyle.NEON -> maxOf(tw, sw("A U D E N", u, 0.4f)) + 2.8f * u to 3.7f * u
            LabelStyle.ONAIR -> 0.7f * u + onAirBadge(u) + 0.8f * u + tw + 1.0f * u + 1.6f * u to 2.8f * u
            LabelStyle.NOWPLAYING -> 3.7f * u + maxOf(tw, sw("NOW PLAYING", u, 0.4f), 4.6f * u) + 0.9f * u to 3.6f * u
            LabelStyle.CHROME -> tw + 4.2f * u to 3.1f * u
            LabelStyle.STAMP -> maxOf(tw, sw("AUDEN  SELECTS", u, 0.38f, 800)) + 2.8f * u to 3.4f * u
            LabelStyle.LED -> tw + 3.8f * u to 3.3f * u
        }
        return Box(w, h, u, title, tw)
    }

    private fun onAirBadge(u: Float) = 1.4f * u + sw("ON AIR", u, 0.62f, 900) + 0.6f * u

    /** Pools of Auden's colours on ink, clipped to [r]: the art the plates that show a record carry. */
    private fun orbArt(canvas: Canvas, r: RectF, radius: Float) {
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
        canvas.drawColor(CoverBrand.INK)
        fun pool(x: Float, y: Float, s: Float, c: Int) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(x, y, s, intArrayOf(c, c and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(x, y, s, p)
        }
        val w = r.width()
        pool(r.left + w * 0.2f, r.top + w * 0.25f, w * 0.8f, CoverBrand.CORAL)
        pool(r.right - w * 0.1f, r.top + w * 0.3f, w * 0.7f, CoverBrand.VIOLET)
        pool(r.left + w * 0.6f, r.bottom, w * 0.75f, CoverBrand.SKY)
        canvas.restore()
    }

    fun size(l: LabelLayer, side: Float): Pair<Float, Float> = box(l, side).let { it.w to it.h }

    /** Drawn centred on the origin. */
    fun draw(canvas: Canvas, side: Float, l: LabelLayer) {
        val b = box(l, side)
        val u = b.u
        val w = b.w
        val h = b.h
        val left = -w / 2f
        val top = -h / 2f
        val rect = RectF(left, top, left + w, top + h)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val ink = CoverBrand.INK
        fun title(x: Float, centreY: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) {
            val p = text(u * if (l.style == LabelStyle.TICKET) 1.25f else if (l.style == LabelStyle.CASSETTE) 1.3f else 1.2f, 800, color)
            p.textAlign = align
            val baseline = centreY - (p.ascent() + p.descent()) / 2f
            canvas.drawText(b.title, 0, b.title.length, x, baseline, p)
        }
        fun small(s: String, x: Float, y: Float, color: Int, size: Float = 0.42f, align: Paint.Align = Paint.Align.LEFT, weight: Int = 700) {
            val p = text(u * size, weight, color, 0.18f)
            p.textAlign = align
            canvas.drawText(s, x, y - (p.ascent() + p.descent()) / 2f, p)
        }

        when (l.style) {
            LabelStyle.GLASS -> {
                fill.color = 0xEEFFFFFF.toInt()
                fill.setShadowLayer(u * 0.6f, 0f, u * 0.2f, 0x44000000)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
                line.color = 0x33000000
                line.strokeWidth = u * 0.06f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.05f, u * 0.05f) }, h / 2f, h / 2f, line)
                CoverBrand.drawMark(canvas, left + 0.9f * u + 1.1f * u, 0f, 2.2f * u, ink)
                title(left + (0.9f + 2.2f + 0.7f) * u, 0f, ink)
            }
            LabelStyle.TICKET -> {
                val stubX = left + w - 3.4f * u
                val body = Path().apply { addRoundRect(rect, u * 0.5f, u * 0.5f, Path.Direction.CW) }
                // The notches the stub tears off at, cut from the top and the bottom edge.
                val notch = Path().apply {
                    addCircle(stubX, top, u * 0.42f, Path.Direction.CW)
                    addCircle(stubX, top + h, u * 0.42f, Path.Direction.CW)
                }
                body.op(notch, Path.Op.DIFFERENCE)
                fill.color = 0xFFFFF1CC.toInt()
                fill.setShadowLayer(u * 0.5f, 0f, u * 0.2f, 0x44000000)
                canvas.drawPath(body, fill)
                line.color = 0x55000000
                line.strokeWidth = u * 0.08f
                line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(u * 0.3f, u * 0.25f), 0f)
                canvas.drawLine(stubX, top + u * 0.7f, stubX, top + h - u * 0.7f, line)
                small("AUDEN  ·  PLAYLIST", left + 1.1f * u, top + 0.95f * u, 0x99000000.toInt())
                title(left + 1.1f * u, 0.15f * u, ink)
                small(year, left + 1.1f * u, top + h - 0.8f * u, 0x88000000.toInt(), 0.4f)
                CoverBrand.drawMark(canvas, left + w - 1.7f * u - 0.1f * u, -0.3f * u, 1.9f * u, ink)
                small(if (l.extra.isBlank()) "♪" else l.extra, left + w - 1.7f * u, top + h - 0.85f * u, ink, 0.55f, Paint.Align.CENTER, 800)
            }
            LabelStyle.SLEEVE -> {
                fill.color = 0xF2141418.toInt()
                fill.setShadowLayer(u * 0.7f, 0f, u * 0.25f, 0x55000000)
                canvas.drawRoundRect(rect, u * 0.7f, u * 0.7f, fill)
                fill.clearShadowLayer()
                val square = RectF(left + 0.8f * u, -1.35f * u, left + 0.8f * u + 2.7f * u, 1.35f * u)
                orbArt(canvas, square, u * 0.45f)
                CoverBrand.drawMark(canvas, square.centerX(), square.centerY(), 2.0f * u, 0xFFFFFFFF.toInt())
                val x = square.right + 0.8f * u
                title(x, -0.35f * u, 0xFFFFFFFF.toInt())
                small("AUDEN  ·  $year", x, 0.95f * u, 0x99FFFFFF.toInt(), 0.42f)
            }
            LabelStyle.CASSETTE -> {
                fill.color = CoverBrand.PAPER
                fill.setShadowLayer(u * 0.5f, 0f, u * 0.2f, 0x44000000)
                canvas.drawRoundRect(rect, u * 0.55f, u * 0.55f, fill)
                fill.clearShadowLayer()
                val band = RectF(left + 0.3f * u, top + 0.3f * u, left + w - 0.3f * u, top + 1.5f * u)
                fill.color = CoverBrand.CORAL
                canvas.drawRoundRect(band, u * 0.35f, u * 0.35f, fill)
                small("SIDE A", band.left + 0.6f * u, band.centerY(), 0xFFFFFFFF.toInt(), 0.5f)
                small("AUDEN  C-60", band.right - 0.6f * u, band.centerY(), 0xFFFFFFFF.toInt(), 0.5f, Paint.Align.RIGHT)
                line.pathEffect = null
                line.color = 0x33000000
                line.strokeWidth = u * 0.05f
                canvas.drawLine(left + 0.8f * u, top + 3.05f * u, left + w - 0.8f * u, top + 3.05f * u, line)
                title(0f, top + 2.3f * u, ink, Paint.Align.CENTER)
                // The window the tape shows through, with its two reels.
                val window = RectF(-1.9f * u, top + h - 1.45f * u, 1.9f * u, top + h - 0.3f * u)
                fill.color = 0xFF1B1B1F.toInt()
                canvas.drawRoundRect(window, u * 0.5f, u * 0.5f, fill)
                fill.color = CoverBrand.PAPER
                canvas.drawCircle(window.left + 0.85f * u, window.centerY(), 0.38f * u, fill)
                canvas.drawCircle(window.right - 0.85f * u, window.centerY(), 0.38f * u, fill)
            }
            LabelStyle.NEON -> {
                fill.color = 0xFF08080C.toInt()
                canvas.drawRoundRect(rect, u * 0.9f, u * 0.9f, fill)
                val tube = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = u * 0.16f
                    color = CoverBrand.ROSE
                    setShadowLayer(u * 0.7f, 0f, 0f, CoverBrand.ROSE)
                }
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.35f, u * 0.35f) }, u * 0.6f, u * 0.6f, tube)
                small("A U D E N", 0f, top + 0.95f * u, 0xAAFFFFFF.toInt(), 0.4f, Paint.Align.CENTER)
                val glow = text(u * 1.2f, 800, 0xFFFFFFFF.toInt()).apply {
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(u * 0.7f, 0f, 0f, CoverBrand.SKY)
                }
                canvas.drawText(b.title, 0, b.title.length, 0f, 0.55f * u - (glow.ascent() + glow.descent()) / 2f, glow)
            }
            LabelStyle.ONAIR -> {
                fill.color = 0xFF111114.toInt()
                fill.setShadowLayer(u * 0.6f, 0f, u * 0.2f, 0x55000000)
                canvas.drawRoundRect(rect, u * 0.7f, u * 0.7f, fill)
                fill.clearShadowLayer()
                val badge = RectF(left + 0.7f * u, -0.85f * u, left + 0.7f * u + onAirBadge(u), 0.85f * u)
                fill.color = 0xFFE5231B.toInt()
                canvas.drawRoundRect(badge, 0.85f * u, 0.85f * u, fill)
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(badge.left + 0.85f * u, badge.centerY(), 0.28f * u, fill)
                small("ON AIR", badge.left + 1.4f * u, badge.centerY(), 0xFFFFFFFF.toInt(), 0.62f, weight = 900)
                title(badge.right + 0.8f * u, 0f, 0xFFFFFFFF.toInt())
                val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CoverBrand.MINT }
                CoverBrand.drawBars(canvas, left + w - 2.3f * u, 0.9f * u, 1.6f * u, 1.8f * u, 4, bars)
            }
            LabelStyle.NOWPLAYING -> {
                fill.color = 0xF21C1C21.toInt()
                fill.setShadowLayer(u * 0.7f, 0f, u * 0.25f, 0x55000000)
                canvas.drawRoundRect(rect, u * 0.95f, u * 0.95f, fill)
                fill.clearShadowLayer()
                val art = RectF(left + 0.7f * u, -1.1f * u, left + 0.7f * u + 2.2f * u, 1.1f * u)
                fill.color = CoverBrand.INK
                canvas.drawRoundRect(art, u * 0.4f, u * 0.4f, fill)
                CoverBrand.drawRing(
                    canvas, art.centerX(), art.centerY(), 1.05f * u, 48,
                    intArrayOf(CoverBrand.SKY, CoverBrand.VIOLET, CoverBrand.ROSE, CoverBrand.CORAL, CoverBrand.SUN, CoverBrand.MINT),
                )
                CoverBrand.drawMark(canvas, art.centerX(), art.centerY(), 0.95f * u, 0xFFFFFFFF.toInt())
                val x = art.right + 0.8f * u
                small("NOW PLAYING", x, top + 0.95f * u, CoverBrand.CORAL, 0.4f)
                title(x, -0.35f * u, 0xFFFFFFFF.toInt())
                val trackLeft = x
                val trackRight = left + w - 0.9f * u
                val y = top + h - 1.2f * u
                fill.color = 0x40FFFFFF
                canvas.drawRoundRect(trackLeft, y - 0.09f * u, trackRight, y + 0.09f * u, u * 0.09f, u * 0.09f, fill)
                fill.color = 0xFFFFFFFF.toInt()
                val at = trackLeft + (trackRight - trackLeft) * 0.4f
                canvas.drawRoundRect(trackLeft, y - 0.09f * u, at, y + 0.09f * u, u * 0.09f, u * 0.09f, fill)
                canvas.drawCircle(at, y, 0.2f * u, fill)
                small("1:12", trackLeft, y + 0.62f * u, 0x88FFFFFF.toInt(), 0.36f)
                small("-2:03", trackRight, y + 0.62f * u, 0x88FFFFFF.toInt(), 0.36f, Paint.Align.RIGHT)
            }
            LabelStyle.CHROME -> {
                // Polished metal: bands of light and shade across it, as a chrome badge catches a room.
                fill.shader = LinearGradient(
                    left, top, left + w * 0.6f, top + h * 1.4f,
                    intArrayOf(0xFFF4F5F8.toInt(), 0xFF9DA3AE.toInt(), 0xFFFFFFFF.toInt(), 0xFF6E7480.toInt(), 0xFFD9DCE2.toInt()),
                    floatArrayOf(0f, 0.32f, 0.5f, 0.72f, 1f), Shader.TileMode.MIRROR,
                )
                fill.setShadowLayer(u * 0.6f, 0f, u * 0.2f, 0x55000000)
                canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
                fill.shader = null
                fill.clearShadowLayer()
                line.color = 0xFF5A606B.toInt()
                line.strokeWidth = u * 0.08f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.04f, u * 0.04f) }, h / 2f, h / 2f, line)
                line.color = 0xCCFFFFFF.toInt()
                line.strokeWidth = u * 0.06f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.2f, u * 0.2f) }, h / 2f, h / 2f, line)
                CoverBrand.drawMark(canvas, left + 0.95f * u + 0.95f * u, 0f, 1.9f * u, ink)
                title(left + 3.4f * u, 0f, ink)
            }
            LabelStyle.STAMP -> {
                canvas.rotate(-3f)
                val red = 0xFFE5342B.toInt()
                line.color = red
                line.strokeWidth = u * 0.16f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.1f, u * 0.1f) }, u * 0.5f, u * 0.5f, line)
                line.strokeWidth = u * 0.07f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.4f, u * 0.4f) }, u * 0.3f, u * 0.3f, line)
                title(0f, -0.15f * u, red, Paint.Align.CENTER)
                small("AUDEN  SELECTS", 0f, top + h - 0.95f * u, red, 0.38f, Paint.Align.CENTER, 800)
            }
            LabelStyle.LED -> {
                // A stereo's display: amber segments on black glass, a level meter over the name.
                fill.color = 0xFF0B0C0E.toInt()
                fill.setShadowLayer(u * 0.6f, 0f, u * 0.2f, 0x66000000)
                canvas.drawRoundRect(rect, u * 0.5f, u * 0.5f, fill)
                fill.clearShadowLayer()
                line.color = 0xFF2A2C31.toInt()
                line.strokeWidth = u * 0.1f
                canvas.drawRoundRect(RectF(rect).apply { inset(u * 0.3f, u * 0.3f) }, u * 0.3f, u * 0.3f, line)
                val amber = 0xFFFFB020.toInt()
                val segments = 16
                val meterLeft = left + 0.9f * u
                val meterWidth = w - 1.8f * u
                for (i in 0 until segments) {
                    val lit = i < segments * 0.7f
                    fill.color = when {
                        !lit -> 0xFF26282C.toInt()
                        i > segments * 0.58f -> 0xFFFF4D2E.toInt()
                        else -> amber
                    }
                    val x0 = meterLeft + meterWidth * i / segments
                    canvas.drawRoundRect(x0, top + 0.7f * u, x0 + meterWidth / segments * 0.7f, top + 1.0f * u, u * 0.06f, u * 0.06f, fill)
                }
                val p = text(u * 1.2f, 800, amber).apply {
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(u * 0.5f, 0f, 0f, amber)
                }
                canvas.drawText(b.title, 0, b.title.length, 0f, 0.45f * u - (p.ascent() + p.descent()) / 2f, p)
            }
        }
    }
}
