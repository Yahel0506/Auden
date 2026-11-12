package dev.auden.dj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.player.DjMeshPalettes
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The DJ's cover as a picture, for the places that can only show a picture: the media notification, the lock
 * screen, Android Auto. The same thing the app draws moving — the DJ's mesh of soft colours with its ring and the
 * echoes of it — held at one frame. Drawn once, kept in the cache, and handed to the DJ's clips as their artwork.
 */
object DjCoverArt {
    private const val SIZE = 640

    /** Bumped when the drawing changes, so the old file is not kept. */
    private const val VERSION = 2

    /** The picture's uri, drawn first if it is not there; null if it could not be made. Off the main thread. */
    fun ensure(context: Context): String? {
        val file = File(context.applicationContext.cacheDir, "dj_cover_v$VERSION.png")
        if (!file.isFile || file.length() == 0L) {
            val ok = runCatching {
                val bitmap = render()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }.isSuccess
            if (!ok) return null
        }
        return Uri.fromFile(file).toString()
    }

    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = SIZE.toFloat()
        canvas.drawColor(0xFF1B1233.toInt())

        // The mesh: four colours, each a soft light in its own corner, the way the DJ's page holds them.
        val colors = DjMeshPalettes.first().colors.map { it.toArgb() }
        val centers = listOf(0.22f to 0.28f, 0.8f to 0.22f, 0.26f to 0.82f, 0.82f to 0.8f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        colors.forEachIndexed { i, color ->
            val (x, y) = centers[i % centers.size]
            paint.shader = RadialGradient(
                x * s, y * s, s * 0.72f,
                intArrayOf(withAlpha(color, 235), withAlpha(color, 0)), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, s, s, paint)
        }
        paint.shader = null

        // The ring and its echoes, as the visualizer draws them at rest with a little push.
        val center = s / 2f
        val radius = s * 0.30f
        val stroke = s * 0.07f
        fun ring(time: Float, dx: Float, dy: Float): Path {
            val path = Path()
            val points = 160
            for (i in 0..points) {
                val a = 2f * PI.toFloat() * i / points
                val wobble = 0.55f * sin(2 * a + 0.9f * time + 0.3f) + 0.32f * sin(3 * a - 1.3f * time + 1.1f) +
                    0.22f * sin(4 * a + 1.7f * time + 2.0f) + 0.13f * sin(5 * a - 2.3f * time)
                val r = radius * (1f + 0.16f * wobble)
                val x = center + dx + r * cos(a)
                val y = center + dy + r * sin(a)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        listOf(
            Triple(-0.6f, 0xFF6EC8FF.toInt(), s * 0.035f to -s * 0.03f),
            Triple(-0.35f, 0xFFFF8FC4.toInt(), -s * 0.03f to s * 0.025f),
            Triple(-0.15f, 0xFF6FE3C0.toInt(), s * 0.015f to s * 0.035f),
        ).forEach { (time, color, offset) ->
            ringPaint.shader = null
            ringPaint.color = withAlpha(color, 140)
            ringPaint.strokeWidth = stroke * 0.96f
            canvas.drawPath(ring(time, offset.first, offset.second), ringPaint)
        }
        val main = ring(0.4f, 0f, 0f)
        ringPaint.color = withAlpha(Color.WHITE, 46)
        ringPaint.strokeWidth = stroke * 2.1f
        canvas.drawPath(main, ringPaint)
        // A shader's colours are scaled by the paint's own alpha: the glow's faint one would dim the ring.
        ringPaint.color = Color.WHITE
        ringPaint.shader = SweepGradient(
            center, center,
            intArrayOf(Color.WHITE, 0xFFFFD6EC.toInt(), Color.WHITE, 0xFFD3E8FF.toInt(), Color.WHITE),
            null,
        )
        ringPaint.strokeWidth = stroke
        canvas.drawPath(main, ringPaint)
        return bitmap
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
