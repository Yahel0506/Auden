package com.music.bitchord.ui.components

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A pill whose ends are elliptical rather than round: each end is as tall as the bar but [reach] times half of that
 * wide, so the rounding does not stay in the corners — it carries on along the top and bottom edges, further in
 * towards the middle, and the bar reads as one soft form instead of a rectangle with round ends.
 *
 * Each quarter is a superellipse of [exponent] (2 is a plain ellipse), so it meets the straight edge without a jump
 * in curvature. A [CornerBasedShape] of half the height, so the backdrop's lens, which reads corner radii off one,
 * takes it for the capsule it resembles.
 */
class ElongatedPillShape(
    val reach: Float = ELONGATED_PILL_REACH,
    val exponent: Float = ELONGATED_PILL_EXPONENT,
) : CornerBasedShape(CornerSize(50), CornerSize(50), CornerSize(50), CornerSize(50)) {

    /** Always fully rounded, whatever corners are asked for. */
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = ElongatedPillShape(reach, exponent)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val r = size.minDimension / 2f
        if (r <= 0f) return Outline.Rectangle(size.toRect())
        val w = size.width
        val h = size.height
        // As far in as the ends may reach, never past the middle of a short bar.
        val rx = (r * reach).coerceAtMost(w / 2f)
        val path = Path()
        path.moveTo(rx, 0f)
        path.lineTo(w - rx, 0f)
        path.quarter(w - rx, r, 1f, -1f, rx, r, towardEdge = false)
        path.lineTo(w, h - r)
        path.quarter(w - rx, h - r, 1f, 1f, rx, r, towardEdge = true)
        path.lineTo(rx, h)
        path.quarter(rx, h - r, -1f, 1f, rx, r, towardEdge = false)
        path.lineTo(0f, r)
        path.quarter(rx, r, -1f, -1f, rx, r, towardEdge = true)
        path.close()
        return Outline.Generic(path)
    }

    private fun Path.quarter(cx: Float, cy: Float, sx: Float, sy: Float, rx: Float, ry: Float, towardEdge: Boolean) {
        val power = 2f / exponent
        for (i in 1..STEPS) {
            val step = if (towardEdge) i else STEPS - i
            val t = step * (Math.PI / 2) / STEPS
            val x = cos(t).toFloat().coerceAtLeast(0f).pow(power)
            val y = sin(t).toFloat().coerceAtLeast(0f).pow(power)
            lineTo(cx + sx * rx * x, cy + sy * ry * y)
        }
    }

    override fun equals(other: Any?): Boolean = other is ElongatedPillShape && other.reach == reach && other.exponent == exponent

    override fun hashCode(): Int = 31 * reach.hashCode() + exponent.hashCode()

    override fun toString(): String = "ElongatedPillShape(reach = $reach, exponent = $exponent)"
}

/** How many times half the bar's height each end reaches in along the bar: 1 is a capsule's round end. */
const val ELONGATED_PILL_REACH = 1.3f

/** The superellipse power of each quarter: 2 is an ellipse. */
const val ELONGATED_PILL_EXPONENT = 2.3f

private const val STEPS = 40

/**
 * The pill that opens into a card: [ElongatedPillShape] while the corners are the full half-height, and a plain
 * rounded rectangle of the corner [corner] gives once they are less — the mini player's own, which grows into the
 * player and back (see PlayerDock.morphCard).
 */
class MorphingElongatedPillShape(
    private val corner: CornerSize,
) : CornerBasedShape(corner, corner, corner, corner) {
    private val pill = ElongatedPillShape()

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = MorphingElongatedPillShape(topStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val half = size.minDimension / 2f
        if (topStart >= half - 0.5f) return pill.createOutline(size, topStart, topEnd, bottomEnd, bottomStart, layoutDirection)
        return Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                size.toRect(), androidx.compose.ui.geometry.CornerRadius(topStart),
            ),
        )
    }
}
