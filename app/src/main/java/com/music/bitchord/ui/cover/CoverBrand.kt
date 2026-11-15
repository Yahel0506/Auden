package com.music.bitchord.ui.cover

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import kotlin.math.cos
import kotlin.math.sin

/**
 * What belongs to Auden, drawn: the A-waveform mark — the app's logo, a line that rises into an A and goes on as a
 * waveform — and the rest of the vocabulary a cover can be made of.
 */
internal object CoverBrand {
    /** Brand colours: the coral the app lives in, and the hues its backdrops and DJ ring are made of. */
    const val CORAL = 0xFFFF453A.toInt()
    const val ROSE = 0xFFFF2D6F.toInt()
    const val VIOLET = 0xFF8E5CF6.toInt()
    const val SKY = 0xFF40C8FF.toInt()
    const val MINT = 0xFF30E0A1.toInt()
    const val SUN = 0xFFFFC53D.toInt()
    const val INK = 0xFF0C0C0F.toInt()
    const val PAPER = 0xFFF6F2EA.toInt()

    // The logo's own path, from the app's vector, in its own units: x 150..1100, y 436..785; the bar of the A apart.
    private const val LINE =
        "M150 709 H235 C270 709 290 685 310 650 L390 470 C405 440 420 436 437 436 C455 436 470 455 480 480 " +
            "L600 785 Q622 812 645 785 L715 590 C725 568 735 570 748 570 C765 570 780 595 790 620 L850 750 " +
            "Q870 790 890 760 L930 680 Q945 660 960 675 Q975 690 1025 709 H1100"
    private const val BAR = "M330 624 H530"

    // Centred on the middle of the mark and scaled so that it is two wide: x from -1 to 1, y about ±0.35.
    private val fit = Matrix().apply { postTranslate(-625f, -610f); postScale(1f / 475f, 1f / 475f) }
    private val line: Path by lazy { Path(PathParser.createPathFromPathData(LINE)).apply { transform(fit) } }
    private val bar: Path by lazy { Path(PathParser.createPathFromPathData(BAR)).apply { transform(fit) } }

    /** The mark [width] wide, centred on ([cx], [cy]), in [color]; [weight] is how thick the line is, 1 being the logo's own. */
    fun drawMark(canvas: Canvas, cx: Float, cy: Float, width: Float, color: Int, weight: Float = 1f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(width / 2f, width / 2f)
        paint.strokeWidth = 44f / 475f * weight
        canvas.drawPath(line, paint)
        paint.strokeWidth = 37f / 475f * weight
        paint.strokeCap = Paint.Cap.BUTT
        canvas.drawPath(bar, paint)
        canvas.restore()
    }

    /** A ring of ticks of every length, the DJ's ring held still. */
    fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, ticks: Int, colors: IntArray, seed: Int = 7) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = radius * 0.07f
        }
        for (i in 0 until ticks) {
            val a = 2f * Math.PI.toFloat() * i / ticks
            // Lengths from two slow waves, so the ring swells and settles round its edge instead of bristling.
            val level = 0.5f + 0.28f * sin(a * 3f + seed) + 0.22f * sin(a * 7f + seed * 2f)
            val inner = radius * 0.62f
            val outer = inner + radius * (0.12f + 0.3f * level)
            paint.color = colors[i * colors.size / ticks]
            canvas.drawLine(cx + inner * cos(a), cy + inner * sin(a), cx + outer * cos(a), cy + outer * sin(a), paint)
        }
    }

    /** Equalizer bars standing on the line y = [baseline], [count] of them across [width]. */
    fun drawBars(canvas: Canvas, left: Float, baseline: Float, width: Float, height: Float, count: Int, paint: Paint, seed: Int = 3) {
        val slot = width / count
        val barWidth = slot * 0.58f
        for (i in 0 until count) {
            val level = 0.3f + 0.7f * (0.5f + 0.5f * sin(i * 1.7f + seed) * cos(i * 0.6f + seed))
            val h = height * level
            canvas.drawRoundRect(left + i * slot + (slot - barWidth) / 2f, baseline - h, left + i * slot + (slot + barWidth) / 2f, baseline, barWidth / 2f, barWidth / 2f, paint)
        }
    }
}
