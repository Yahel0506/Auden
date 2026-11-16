package com.music.bitchord.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cover of "Mis favoritas", the playlist of what is liked on this device: a soft diagonal gradient from light red
 * through light purple to light blue, with a filled white star in the middle. Drawn once into a file the app's image
 * loader reads like any other cover.
 */
object FavoritesCover {
    private const val SIZE = 640
    private const val FILE = "favorites_cover_v1.png"

    /** The cover as a `file:` uri, drawn first if it is not there yet. */
    fun uri(context: Context): String {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) runCatching { draw(file) }
        return android.net.Uri.fromFile(file).toString()
    }

    private fun draw(file: File) {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                intArrayOf(0xFFFF8FA3.toInt(), 0xFFC79CFF.toInt(), 0xFF8CC8FF.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), background)
        // A five-pointed star, its points a little rounded.
        val cx = SIZE / 2f
        val cy = SIZE / 2f + 8f
        val outer = SIZE * 0.30f
        val inner = outer * 0.48f
        val star = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.PI / 5 * i - Math.PI / 2
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
            pathEffect = CornerPathEffect(SIZE * 0.035f)
            setShadowLayer(SIZE * 0.03f, 0f, SIZE * 0.012f, 0x33000000)
        }
        canvas.drawPath(star, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
