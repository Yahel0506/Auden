package com.music.bitchord.ui.cover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What can be done to a picture on the cover, any number of them together: each effect has a strength from 0 to 1,
 * and they run in a fixed order — colour first, then what is done to the picture's detail, then the light, then the
 * cut of its outline — so the result does not depend on the order they were switched on in.
 */
internal object CoverImageFx {
    private const val WORK_SIDE = 900
    private val cache = LruCache<String, Bitmap>(6)

    fun render(layer: ImageLayer, useCache: Boolean = true): Bitmap {
        if (layer.effects.isEmpty() && layer.shape == ImageShape.RECTANGLE) return layer.bitmap
        val key = "${System.identityHashCode(layer.bitmap)}|${layer.shape}|" + layer.effects.entries.sortedBy { it.key }.joinToString(",") { "${it.key}${(it.value * 100).roundToInt()}" }
        if (useCache) cache.get(key)?.let { return it }
        var b = scaled(layer.bitmap)
        val fx = layer.effects
        fx[ImageEffect.GRAYSCALE]?.let { b = colorize(b, ColorMatrix().apply { setSaturation(1f - it) }) }
        fx[ImageEffect.SEPIA]?.let { b = blend(b, colorize(b, sepia()), it) }
        fx[ImageEffect.DUOTONE]?.let { b = blend(b, duotone(b), it) }
        fx[ImageEffect.INVERT]?.let { b = blend(b, colorize(b, invert()), it) }
        fx[ImageEffect.POSTERIZE]?.let { b = posterize(b, it) }
        fx[ImageEffect.BLUR]?.let { b = blur(b, it) }
        fx[ImageEffect.PIXELATE]?.let { b = pixelate(b, it) }
        fx[ImageEffect.HALFTONE]?.let { b = halftone(b, it) }
        fx[ImageEffect.GLITCH]?.let { b = glitch(b, it) }
        fx[ImageEffect.GLOW]?.let { b = glow(b, it) }
        fx[ImageEffect.VIGNETTE]?.let { b = vignette(b, it) }
        b = shaped(b, layer.shape)
        if (useCache) cache.put(key, b)
        return b
    }

    private fun scaled(src: Bitmap): Bitmap {
        val big = max(src.width, src.height)
        val copy = if (big <= WORK_SIDE) src else Bitmap.createScaledBitmap(src, src.width * WORK_SIDE / big, src.height * WORK_SIDE / big, true)
        return copy.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun colorize(b: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** [top] laid over [base] at [amount]. */
    private fun blend(base: Bitmap, top: Bitmap, amount: Float): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(top, 0f, 0f, Paint().apply { alpha = (amount.coerceIn(0f, 1f) * 255f).roundToInt() })
        return out
    }

    private fun sepia() = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))

    private fun invert() = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))

    /** Shadows to deep violet, lights to coral: the picture's tones mapped onto two of Auden's colours. */
    private fun duotone(b: Bitmap): Bitmap {
        val dark = CoverBrand.VIOLET and 0xFFFFFF
        val light = CoverBrand.SUN
        val dr = (dark shr 16 and 0xFF) * 0.35f
        val dg = (dark shr 8 and 0xFF) * 0.35f
        val db = (dark and 0xFF) * 0.35f
        val lr = (light shr 16 and 0xFF).toFloat()
        val lg = (light shr 8 and 0xFF).toFloat()
        val lb = (light and 0xFF).toFloat()
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val map = ColorMatrix(floatArrayOf(
            (lr - dr) / 255f, 0f, 0f, 0f, dr,
            0f, (lg - dg) / 255f, 0f, 0f, dg,
            0f, 0f, (lb - db) / 255f, 0f, db,
            0f, 0f, 0f, 1f, 0f,
        ))
        gray.postConcat(map)
        return colorize(b, gray)
    }

    private fun posterize(b: Bitmap, amount: Float): Bitmap {
        val levels = (8 - amount * 6f).roundToInt().coerceIn(2, 8)
        val step = 255f / (levels - 1)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        fun q(c: Int) = ((c / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or (q(c shr 16 and 0xFF) shl 16) or (q(c shr 8 and 0xFF) shl 8) or q(c and 0xFF)
        }
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return out
    }

    /** Taken down a few times by half and brought back up, which is a soft blur at a fraction of the cost. */
    private fun blur(b: Bitmap, amount: Float): Bitmap {
        var small = b
        var halves = 1 + (amount * 4f).roundToInt()
        while (halves-- > 0 && min(small.width, small.height) > 8) {
            small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
        }
        return Bitmap.createScaledBitmap(small, b.width, b.height, true)
    }

    private fun pixelate(b: Bitmap, amount: Float): Bitmap {
        val block = (3f + amount * 40f).roundToInt()
        val small = Bitmap.createScaledBitmap(b, max(2, b.width / block), max(2, b.height / block), false)
        return Bitmap.createScaledBitmap(small, b.width, b.height, false)
    }

    /** Dots on black, each as large as the picture is dark under it and in the colour that is there. */
    private fun halftone(b: Bitmap, amount: Float): Bitmap {
        val cell = (6f + amount * 16f)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF000000.toInt())
        val small = Bitmap.createScaledBitmap(b, max(1, (b.width / cell).roundToInt()), max(1, (b.height / cell).roundToInt()), true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            val lum = ((c shr 16 and 0xFF) * 0.3f + (c shr 8 and 0xFF) * 0.59f + (c and 0xFF) * 0.11f) / 255f
            paint.color = c or 0xFF000000.toInt()
            canvas.drawCircle((x + 0.5f) * b.width / small.width, (y + 0.5f) * b.height / small.height, cell * (0.12f + 0.5f * lum), paint)
        }
        return out
    }

    private fun glitch(b: Bitmap, amount: Float): Bitmap {
        val shift = amount * 0.05f * b.width
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        fun channel(r: Float, g: Float, bl: Float) = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, g, 0f, 0f, 0f,
                0f, 0f, bl, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawColor(0xFF000000.toInt())
        canvas.drawBitmap(b, -shift, 0f, channel(1f, 0f, 0f))
        canvas.drawBitmap(b, 0f, 0f, channel(0f, 1f, 0f))
        canvas.drawBitmap(b, shift, 0f, channel(0f, 0f, 1f))
        // A few torn strips across it.
        val rng = Random(b.width + b.height)
        val copy = out.copy(Bitmap.Config.ARGB_8888, false)
        repeat(2 + (amount * 6f).roundToInt()) {
            val y0 = rng.nextInt(b.height - 4)
            val h = 3 + rng.nextInt(max(4, (b.height * 0.06f).roundToInt()))
            val dx = (rng.nextFloat() * 2f - 1f) * shift * 3f
            canvas.save()
            canvas.clipRect(0f, y0.toFloat(), b.width.toFloat(), (y0 + h).toFloat())
            canvas.drawBitmap(copy, dx, 0f, null)
            canvas.restore()
        }
        return out
    }

    /** A bloom: the picture, blurred, laid over itself so that its lights spill. */
    private fun glow(b: Bitmap, amount: Float): Bitmap {
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(blur(b, 0.8f), 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            alpha = (amount * 255f).roundToInt()
        })
        return out
    }

    private fun vignette(b: Bitmap, amount: Float): Bitmap {
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply {
            shader = RadialGradient(
                b.width / 2f, b.height / 2f, max(b.width, b.height) * 0.75f,
                intArrayOf(0x00000000, 0x00000000, ((amount * 235f).roundToInt() shl 24)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        Canvas(out).drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), paint)
        return out
    }

    private fun shaped(b: Bitmap, shape: ImageShape): Bitmap {
        if (shape == ImageShape.RECTANGLE) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        if (shape == ImageShape.CIRCLE) {
            val side = min(b.width, b.height) / 2f
            canvas.drawCircle(b.width / 2f, b.height / 2f, side, mask)
        } else {
            val r = min(b.width, b.height) * 0.14f
            canvas.drawRoundRect(rect, r, r, mask)
        }
        canvas.drawBitmap(b, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
        return out
    }
}
