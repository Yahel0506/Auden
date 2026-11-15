package com.music.bitchord.ui.cover

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The patterned backgrounds: a mesh of soft light, aurora curtains, a sunburst, stripes, layered waves, halftone dots
 * and ripples — each made from the cover's own colours, laid out from a seed so a new seed is a new arrangement.
 */
internal object CoverBackgrounds {
    private fun at(colors: List<Int>, i: Int) = colors[((i % colors.size) + colors.size) % colors.size]

    private fun darker(c: Int, k: Float) = CoverPalette.mix(c, 0xFF000000.toInt(), k)

    fun draw(canvas: Canvas, side: Float, style: BackgroundStyle, colors: List<Int>, seed: Int) {
        val rng = Random(seed)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style) {
            BackgroundStyle.MESH -> {
                canvas.drawColor(darker(colors[0], 0.25f))
                // Soft pools of each colour, overlapping, as the player's backdrop is.
                for (i in 0 until 6) {
                    val x = side * rng.nextFloat()
                    val y = side * rng.nextFloat()
                    val r = side * (0.45f + rng.nextFloat() * 0.45f)
                    val c = at(colors, i)
                    paint.shader = RadialGradient(x, y, r, intArrayOf(c, c and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                    canvas.drawCircle(x, y, r, paint)
                }
            }
            BackgroundStyle.AURORA -> {
                canvas.drawColor(darker(colors[0], 0.82f))
                val strips = 160
                val w = side / strips
                for (band in 0 until 3) {
                    val c = at(colors, band)
                    val base = side * (0.25f + band * 0.2f)
                    val amp = side * (0.06f + rng.nextFloat() * 0.08f)
                    val freq = 1f + rng.nextFloat() * 1.5f
                    val phase = rng.nextFloat() * 6f
                    val tall = side * (0.35f + rng.nextFloat() * 0.2f)
                    for (i in 0 until strips) {
                        val u = i / strips.toFloat()
                        val top = base + amp * sin(u * 2f * PI.toFloat() * freq + phase)
                        val glow = 0.55f + 0.45f * sin(u * 9f + phase * 2f)
                        val alpha = (glow * 200f).toInt().coerceIn(0, 255)
                        paint.shader = LinearGradient(0f, top, 0f, top + tall, intArrayOf(c and 0x00FFFFFF, (alpha shl 24) or (c and 0xFFFFFF), c and 0x00FFFFFF), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                        canvas.drawRect(i * w, top, (i + 1) * w + 1f, top + tall, paint)
                    }
                }
                paint.xfermode = null
            }
            BackgroundStyle.SUNBURST -> {
                canvas.drawColor(colors[0])
                val cx = side * (0.3f + rng.nextFloat() * 0.4f)
                val cy = side * (0.55f + rng.nextFloat() * 0.35f)
                val rays = 18 + rng.nextInt(10)
                val r = side * 1.6f
                paint.color = at(colors, 1)
                for (i in 0 until rays step 2) {
                    val a0 = 2f * PI.toFloat() * i / rays
                    val a1 = 2f * PI.toFloat() * (i + 1) / rays
                    canvas.drawPath(Path().apply {
                        moveTo(cx, cy); lineTo(cx + r * cos(a0), cy + r * sin(a0)); lineTo(cx + r * cos(a1), cy + r * sin(a1)); close()
                    }, paint)
                }
                paint.shader = RadialGradient(cx, cy, side * 0.75f, intArrayOf(0x00000000, 0x55000000), null, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side, side, paint)
            }
            BackgroundStyle.STRIPES -> {
                canvas.save()
                canvas.rotate(if (rng.nextBoolean()) -28f else 28f, side / 2f, side / 2f)
                val band = side / (6 + rng.nextInt(6))
                var y = -side
                var i = 0
                while (y < side * 2f) {
                    paint.color = at(colors, i++)
                    canvas.drawRect(-side, y, side * 2f, y + band, paint)
                    y += band
                }
                canvas.restore()
            }
            BackgroundStyle.WAVES -> {
                canvas.drawColor(darker(colors[0], 0.15f))
                val layers = 5
                for (k in 0 until layers) {
                    val c = CoverPalette.mix(at(colors, 0), at(colors, colors.size - 1), k / (layers - 1f))
                    val baseY = side * (0.18f + k * 0.18f)
                    val amp = side * (0.04f + rng.nextFloat() * 0.05f)
                    val freq = 1f + rng.nextFloat() * 1.2f
                    val phase = rng.nextFloat() * 6f
                    val path = Path().apply {
                        moveTo(0f, side)
                        for (i in 0..60) {
                            val x = side * i / 60f
                            lineTo(x, baseY + amp * sin(i / 60f * 2f * PI.toFloat() * freq + phase))
                        }
                        lineTo(side, side); close()
                    }
                    paint.color = c
                    canvas.drawPath(path, paint)
                }
            }
            BackgroundStyle.DOTS -> {
                canvas.drawColor(colors[0])
                val n = 16
                val cell = side / n
                val ink = at(colors, 1)
                paint.color = ink
                for (j in 0 until n) for (i in 0 until n) {
                    // Larger toward one corner: a halftone fade.
                    val t = ((i + j) / (2f * (n - 1)))
                    canvas.drawCircle((i + 0.5f) * cell, (j + 0.5f) * cell, cell * 0.5f * (0.12f + 0.88f * t), paint)
                }
            }
            BackgroundStyle.RIPPLE -> {
                val cx = side * (0.2f + rng.nextFloat() * 0.6f)
                val cy = side * (0.2f + rng.nextFloat() * 0.6f)
                val rings = 12
                for (k in rings downTo 0) {
                    paint.color = CoverPalette.mix(at(colors, 0), at(colors, colors.size - 1), (k % 2) * 0.85f + k / rings.toFloat() * 0.15f)
                    canvas.drawCircle(cx, cy, side * 1.5f * k / rings, paint)
                }
            }
            else -> canvas.drawColor(colors[0])
        }
    }

    private var noise: Bitmap? = null

    /** Monochrome noise, tiled over the cover and overlaid at [amount]. */
    fun grain(canvas: Canvas, side: Float, amount: Float) {
        val tile = noise ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bmp ->
            val rng = Random(42)
            val px = IntArray(256 * 256) {
                val v = rng.nextInt(256)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bmp.setPixels(px, 0, 256, 0, 0, 256, 256)
            noise = bmp
        }
        val paint = Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            alpha = (amount.coerceIn(0f, 1f) * 200f).toInt()
        }
        canvas.drawRect(0f, 0f, side, side, paint)
    }
}
