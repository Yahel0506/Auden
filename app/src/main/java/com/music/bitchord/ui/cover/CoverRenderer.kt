package com.music.bitchord.ui.cover

import android.graphics.Bitmap
import android.graphics.PorterDuffXfermode
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuff
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws a cover: the background, then each layer in order, on any [Canvas].
 *
 * One drawing path for the editor's preview and for the picture that is kept, so what is saved is what was on
 * screen, at any size. Everything is in terms of [side], the cover's edge, so the same design draws the same at
 * every resolution. Texts are set once into a bitmap, effects included, and kept — dragging one around redraws
 * that bitmap and sets nothing again.
 */
object CoverRenderer {
    private val textCache = LruCache<String, Bitmap>(16)

    fun render(side: Int, background: CoverBackground, layers: List<CoverLayer>): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        draw(Canvas(out), side.toFloat(), background, layers)
        return out
    }

    fun draw(canvas: Canvas, side: Float, background: CoverBackground, layers: List<CoverLayer>) {
        drawBackground(canvas, side, background)
        layers.forEach { drawLayer(canvas, side, it) }
    }

    /** A layer's size, in pixels of a cover [side] wide, before its own scale and rotation. */
    fun sizeOf(layer: CoverLayer, side: Float): Pair<Float, Float> = when (layer) {
        is TextLayer -> textBitmap(layer, side).let { it.width.toFloat() to it.height.toFloat() }
        is LabelLayer -> CoverLabels.size(layer, side)
        is ImageLayer -> (side * 0.62f).let { it to it * layer.bitmap.height / layer.bitmap.width }
        is StickerLayer -> (side * 0.3f).let { it to it * CoverStickers.aspect(layer.kind) }
    }

    /** Whether the point ([x], [y]) in cover pixels is on [layer]. */
    fun hits(layer: CoverLayer, side: Float, x: Float, y: Float): Boolean {
        val (w, h) = sizeOf(layer, side)
        val dx = x - layer.cx * side
        val dy = y - layer.cy * side
        val r = Math.toRadians(-layer.rotation.toDouble())
        val lx = (dx * cos(r) - dy * sin(r)).toFloat() / layer.scale
        val ly = (dx * sin(r) + dy * cos(r)).toFloat() / layer.scale
        return abs(lx) <= w / 2f && abs(ly) <= h / 2f
    }

    // ---- Background ----

    private fun drawBackground(canvas: Canvas, side: Float, background: CoverBackground) {
        val colors = background.colors.ifEmpty { listOf(0xFF000000.toInt()) }.let { if (it.size == 1) listOf(it[0], it[0]) else it }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (background.style) {
            BackgroundStyle.SOLID -> canvas.drawColor(colors.first())
            BackgroundStyle.VERTICAL -> {
                paint.shader = LinearGradient(0f, 0f, 0f, side, colors.toIntArray(), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side, side, paint)
            }
            BackgroundStyle.HORIZONTAL -> {
                paint.shader = LinearGradient(0f, 0f, side, 0f, colors.toIntArray(), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side, side, paint)
            }
            BackgroundStyle.RADIAL -> {
                paint.shader = RadialGradient(side / 2f, side / 2f, side * 0.72f, colors.toIntArray(), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side, side, paint)
            }
            BackgroundStyle.CORNERS -> drawCorners(canvas, side, colors)
            else -> CoverBackgrounds.draw(canvas, side, background.style, colors, background.seed)
        }
        if (background.grain > 0.01f) CoverBackgrounds.grain(canvas, side, background.grain)
    }

    /** Four corner colours blended across the square, from a fine mesh of vertex colours. */
    private fun drawCorners(canvas: Canvas, side: Float, colors: List<Int>) {
        val bl = colors[0]
        val tr = colors.last()
        val (tl, br) = when (colors.size) {
            2 -> CoverPalette.mix(colors[0], colors[1], 0.5f).let { it to it }
            3 -> colors[1] to colors[1]
            else -> colors[3] to colors[1]
        }
        val n = 10
        val verts = FloatArray((n + 1) * (n + 1) * 2)
        val cols = IntArray((n + 1) * (n + 1))
        for (j in 0..n) for (i in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            val index = j * (n + 1) + i
            verts[index * 2] = u * side
            verts[index * 2 + 1] = v * side
            val top = CoverPalette.mix(tl, tr, u)
            val bottom = CoverPalette.mix(bl, br, u)
            cols[index] = CoverPalette.mix(top, bottom, v)
        }
        val indices = ShortArray(n * n * 6)
        var k = 0
        for (j in 0 until n) for (i in 0 until n) {
            val a = (j * (n + 1) + i).toShort()
            val b = (j * (n + 1) + i + 1).toShort()
            val c = ((j + 1) * (n + 1) + i).toShort()
            val d = ((j + 1) * (n + 1) + i + 1).toShort()
            indices[k++] = a; indices[k++] = b; indices[k++] = c
            indices[k++] = b; indices[k++] = d; indices[k++] = c
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, null, 0, cols, 0, indices, 0, indices.size, Paint())
    }

    // ---- Layers ----

    /** A text's own bitmap, effects and all — for the tiles that preview the effects. */
    fun textPreview(layer: TextLayer, side: Float): Bitmap = textBitmap(layer, side)

    /** One layer on its own, placed as it says — for the tiles that preview labels and stickers. */
    fun drawLayer(canvas: Canvas, side: Float, layer: CoverLayer) {
        val (w, h) = sizeOf(layer, side)
        canvas.save()
        canvas.translate(layer.cx * side, layer.cy * side)
        canvas.rotate(layer.rotation)
        canvas.scale(layer.scale, layer.scale)
        when (layer) {
            is TextLayer -> canvas.drawBitmap(
                textBitmap(layer, side), -w / 2f, -h / 2f,
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = (layer.opacity * 255f).roundToInt() },
            )
            is ImageLayer -> canvas.drawBitmap(
                CoverImageFx.render(layer), null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            is LabelLayer -> CoverLabels.draw(canvas, side, layer)
            is StickerLayer -> {
                canvas.scale(w / 2f, w / 2f)
                CoverStickers.draw(canvas, layer.kind)
            }
        }
        canvas.restore()
    }

    // ---- Text ----

    private fun typefaceOf(weight: Int): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
        } else {
            Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun textBitmap(l: TextLayer, side: Float): Bitmap {
        val key = "${l.text}|${l.size}|${l.weight}|${l.slant}|${l.fill}|${l.stroke}|${l.effect}|${l.effectA}|${l.effectB}|" +
            "${l.spacing}|${l.leading}|${l.caps}|${l.font}|${l.stretch}|${side.toInt()}"
        textCache.get(key)?.let { return it }
        val set = setText(l, side)
        val shaped = when (l.effect) {
            TextEffect.NONE -> set
            TextEffect.SHADOW -> shadow(set, l.effectA, l.effectB)
            TextEffect.NEON -> neon(set, l.fill, l.effectA, l.effectB)
            TextEffect.GLITCH -> glitch(set, l.effectA, l.effectB)
            TextEffect.EXTRUDE -> extrude(set, l.fill, l.effectA, l.effectB)
            TextEffect.ECHO -> echo(set, l.effectA, l.effectB)
            TextEffect.ARC -> arc(set, l.effectA, l.effectB)
            TextEffect.WAVE -> verticalWave(set, l.effectA, l.effectB)
            TextEffect.STACK -> stack(set, l.effectA, l.effectB)
            TextEffect.FLAG -> wave(stack(set, l.effectA, l.effectB))
            TextEffect.DISPLACE -> displace(set, l.effectA, l.effectB)
            TextEffect.SPHERE -> sphere(set, l.effectA)
        }
        textCache.put(key, shaped)
        return shaped
    }

    private fun setText(l: TextLayer, side: Float): Bitmap {
        val px = side * 0.15f * (l.size / 100f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = CoverFonts.typeface(l.font, l.weight)
            textSize = px
            color = l.fill
            textSkewX = -l.slant * 0.3f
            letterSpacing = l.spacing
            textScaleX = l.stretch
        }
        val text = (if (l.caps) l.text.uppercase() else l.text).ifBlank { " " }
        // A wide setting is let run wider rather than wrapped onto more lines: the layer's own scale brings it back.
        val maxWidth = (side * 0.9f * max(1f, l.stretch)).toInt()
        fun layoutOf(p: TextPaint, width: Int) = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, l.leading)
            .build()
        val probe = layoutOf(paint, maxWidth)
        var widest = 1f
        for (i in 0 until probe.lineCount) widest = max(widest, probe.getLineWidth(i))
        val width = ceil(widest).toInt().coerceAtLeast(1)
        val layout = layoutOf(paint, width)
        val strokeWidth = if (l.stroke != null) px * 0.1f else 0f
        val pad = ceil(px * 0.2f + strokeWidth).toInt()
        val out = Bitmap.createBitmap(width + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(pad.toFloat(), pad.toFloat())
        if (l.stroke != null) {
            val outline = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth * 2f
                strokeJoin = Paint.Join.ROUND
                color = l.stroke
            }
            layoutOf(outline, width).draw(canvas)
        }
        layout.draw(canvas)
        return out
    }

    // ---- Effects added to the text, each a new bitmap grown to hold what it adds ----

    private fun padded(b: Bitmap, pad: Int): Pair<Bitmap, Canvas> {
        val out = Bitmap.createBitmap(b.width + pad * 2, b.height + pad * 2, Bitmap.Config.ARGB_8888)
        return out to Canvas(out)
    }

    private fun tint(color: Int) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /** A soft shadow, [distance] down and to the right and [blur] wide. */
    private fun shadow(b: Bitmap, distance: Float, blur: Float): Bitmap {
        val r = max(1f, blur)
        val pad = ceil(distance + r * 2f).toInt()
        val (out, canvas) = padded(b, pad)
        val offset = IntArray(2)
        val alpha = b.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) }, offset)
        canvas.drawBitmap(alpha, pad + offset[0] + distance, pad + offset[1] + distance, Paint().apply { color = 0x99000000.toInt() })
        canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** A glow in the text's own colour, layered thicker the more [intensity]; the letters burn white at the core. */
    private fun neon(b: Bitmap, color: Int, radius: Float, intensity: Float): Bitmap {
        val r = max(2f, radius * 1.4f)
        val pad = ceil(r * 3f).toInt()
        val (out, canvas) = padded(b, pad)
        val glowColor = if (color == 0xFFFFFFFF.toInt()) CoverBrand.SKY else color
        for (i in 0 until (1 + intensity.roundToInt() / 2).coerceIn(1, 6)) {
            val spread = r * (1f + i * 0.55f)
            val offset = IntArray(2)
            val alpha = b.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(spread, BlurMaskFilter.Blur.NORMAL) }, offset)
            canvas.drawBitmap(alpha, (pad + offset[0]).toFloat(), (pad + offset[1]).toFloat(), Paint().apply { this.color = glowColor })
        }
        canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), tint(glowColor))
        canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }.also {
            it.colorFilter = PorterDuffColorFilter(0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
        })
        return out
    }

    /** The colour channels pulled apart, cyan one way and magenta the other, and the text torn into [cuts] strips. */
    private fun glitch(b: Bitmap, split: Float, cuts: Float): Bitmap {
        val d = max(1f, split * 0.012f * b.width)
        val pad = ceil(d * 3f).toInt()
        val (out, canvas) = padded(b, pad)
        val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val cyan = tint(0xFF00FFFF.toInt()).apply { xfermode = add }
        val magenta = tint(0xFFFF00FF.toInt()).apply { xfermode = add }
        canvas.drawBitmap(b, pad - d, pad.toFloat(), cyan)
        canvas.drawBitmap(b, pad + d, pad.toFloat(), magenta)
        val n = cuts.roundToInt().coerceIn(0, 12)
        val rng = Random(b.width * 17 + b.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (n == 0) canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), paint)
        for (i in 0 until n) {
            val y0 = b.height * i / n
            val y1 = b.height * (i + 1) / n
            val shift = if (i % 2 == 0) 0f else (rng.nextFloat() * 2f - 1f) * d * 2.5f
            canvas.drawBitmap(
                b, android.graphics.Rect(0, y0, b.width, y1),
                RectF(pad + shift, (pad + y0).toFloat(), pad + shift + b.width, (pad + y1).toFloat()), paint,
            )
        }
        return out
    }

    /** The letters pushed back into depth along [angle] degrees, in a darker shade of the text. */
    private fun extrude(b: Bitmap, color: Int, depth: Float, angle: Float): Bitmap {
        val steps = depth.roundToInt().coerceIn(1, 60)
        val pad = steps + 2
        val (out, canvas) = padded(b, pad)
        val dx = cos(Math.toRadians(angle.toDouble())).toFloat()
        val dy = sin(Math.toRadians(angle.toDouble())).toFloat()
        val dark = tint(CoverPalette.mix(color, 0xFF000000.toInt(), 0.6f))
        for (i in steps downTo 1) canvas.drawBitmap(b, pad - dx * i * 0.5f, pad - dy * i * 0.5f, dark)
        canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** Fainter copies trailing off [distance] to the lower right. */
    private fun echo(b: Bitmap, count: Float, distance: Float): Bitmap {
        val n = count.roundToInt().coerceIn(1, 8)
        val pad = ceil(distance * n).toInt() + 2
        val (out, canvas) = padded(b, pad)
        for (i in n downTo 1) {
            canvas.drawBitmap(b, pad + distance * i, pad + distance * i, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * (1f - i / (n + 1f)) * 0.7f).roundToInt() })
        }
        canvas.drawBitmap(b, pad.toFloat(), pad.toFloat(), null)
        return out
    }

    /** Bent along an arch ([bend] positive curves it up in the middle) and, with [taper], thinner toward its ends. */
    private fun arc(b: Bitmap, bend: Float, taper: Float): Bitmap {
        val cols = 48
        val sag = bend / 10f * b.height * 1.6f
        val outH = (b.height + abs(sag) * 2f).roundToInt().coerceAtLeast(b.height)
        val verts = FloatArray((cols + 1) * 4)
        for (i in 0..cols) {
            val u = 2f * i / cols - 1f
            val off = -sag * (1f - u * u)
            val s = 1f - taper / 10f * 0.55f * u * u
            val mid = outH / 2f + off + sag * 0.5f
            val x = b.width * i / cols.toFloat()
            verts[i * 2] = x
            verts[i * 2 + 1] = mid - b.height / 2f * s
            verts[(cols + 1 + i) * 2] = x
            verts[(cols + 1 + i) * 2 + 1] = mid + b.height / 2f * s
        }
        val out = Bitmap.createBitmap(b.width, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmapMesh(b, cols, 1, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Every column lifted and dropped along a sine: [amplitude] tall, [frequency] tenths of a wave across. */
    private fun verticalWave(b: Bitmap, amplitude: Float, frequency: Float): Bitmap {
        val cols = 64
        val amp = amplitude / 100f * b.height * 1.4f
        val outH = (b.height + amp * 2f).roundToInt()
        val verts = FloatArray((cols + 1) * 4)
        for (i in 0..cols) {
            val u = i / cols.toFloat()
            val off = amp * sin(u * 2f * PI.toFloat() * frequency / 2f)
            val x = b.width * u
            verts[i * 2] = x
            verts[i * 2 + 1] = amp + off
            verts[(cols + 1 + i) * 2] = x
            verts[(cols + 1 + i) * 2 + 1] = amp + off + b.height
        }
        val out = Bitmap.createBitmap(b.width, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmapMesh(b, cols, 1, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** [count] copies one under another, each [spacing] further on — less than a line apart, so they run together. */
    private fun stack(b: Bitmap, count: Float, spacing: Float): Bitmap {
        val n = count.roundToInt().coerceIn(1, 8)
        val step = (spacing / 15f).coerceIn(0.08f, 1.2f)
        val out = Bitmap.createBitmap(b.width, (b.height * (1f + (n - 1) * step)).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until n) canvas.drawBitmap(b, 0f, i * b.height * step, paint)
        return out
    }

    /** Every row carried sideways along a sine, like cloth in a wind. */
    private fun wave(b: Bitmap): Bitmap {
        val amp = b.width * 0.06f
        val rows = 48
        val out = Bitmap.createBitmap((b.width + amp * 2f).roundToInt(), b.height, Bitmap.Config.ARGB_8888)
        val verts = FloatArray((rows + 1) * 4)
        for (r in 0..rows) {
            val y = b.height * r / rows.toFloat()
            val dx = amp * sin(r / rows.toFloat() * 2f * PI.toFloat() * 2.2f)
            verts[r * 4] = amp + dx
            verts[r * 4 + 1] = y
            verts[r * 4 + 2] = amp + b.width + dx
            verts[r * 4 + 3] = y
        }
        Canvas(out).drawBitmapMesh(b, 1, rows, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Cut into [frequency]-many bands, each shifted along by up to [intensity] percent of the width. */
    private fun displace(b: Bitmap, frequency: Float, intensity: Float): Bitmap {
        val slices = (3 + frequency * 4f).roundToInt().coerceIn(3, 40)
        val maxShift = b.width * intensity / 100f
        val pad = ceil(maxShift).toInt()
        val out = Bitmap.createBitmap(b.width + pad * 2, b.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rng = Random(b.width * 31 + b.height)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until slices) {
            val y0 = b.height * i / slices
            val y1 = b.height * (i + 1) / slices
            val shift = pad + (rng.nextFloat() * 2f - 1f) * maxShift
            canvas.drawBitmap(
                b, android.graphics.Rect(0, y0, b.width, y1),
                RectF(shift, y0.toFloat(), shift + b.width, y1.toFloat()), paint,
            )
        }
        return out
    }

    /** Swollen outward from the middle, as if wrapped round a ball. */
    private fun sphere(b: Bitmap, intensity: Float): Bitmap {
        val k = (intensity / 10f * 0.7f).coerceIn(0.05f, 0.9f)
        val w2 = (b.width * (1f + k)).roundToInt()
        val h2 = (b.height * (1f + k)).roundToInt()
        val n = 20
        val verts = FloatArray((n + 1) * (n + 1) * 2)
        for (j in 0..n) for (i in 0..n) {
            val u = (i / n.toFloat() - 0.5f) * 2f
            val v = (j / n.toFloat() - 0.5f) * 2f
            val r = min(1f, hypot(u, v) / 1.4142f)
            val gain = 1f + k * (1f - r)
            val index = j * (n + 1) + i
            verts[index * 2] = w2 / 2f + u * b.width / 2f * gain
            verts[index * 2 + 1] = h2 / 2f + v * b.height / 2f * gain
        }
        val out = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmapMesh(b, n, n, verts, 0, null, 0, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
