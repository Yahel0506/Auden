package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot

/**
 * A circle spreading from [origin] (in the shape's own coordinates), [progress] of the way to
 * covering the whole area: what clips a screen that is taking over another from the corner a button
 * sits in. At 1 it reaches past the farthest corner, so nothing is left clipped.
 */
class CornerReveal(private val progress: Float, private val origin: Offset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val farthest = maxOf(
            hypot(origin.x, origin.y),
            hypot(size.width - origin.x, origin.y),
            hypot(origin.x, size.height - origin.y),
            hypot(size.width - origin.x, size.height - origin.y),
        )
        val radius = farthest * progress.coerceIn(0f, 1f)
        return Outline.Generic(Path().apply { addOval(Rect(origin, radius)) })
    }
}
