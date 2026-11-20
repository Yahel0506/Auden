package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The marks of the services Auden reads through their web players, drawn as one-colour glyphs like the rest of the rows. */
object ServiceGlyphs {
    /** Tidal's: four diamonds, three across and one under the middle. */
    val Tidal: ImageVector by lazy {
        ImageVector.Builder("Tidal", 24.dp, 24.dp, 24f, 24f).apply {
            fun diamond(cx: Float, cy: Float, r: Float) = path(fill = SolidColor(Color.Black)) {
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }
            diamond(5.2f, 8f, 3.6f)
            diamond(12f, 8f, 3.6f)
            diamond(18.8f, 8f, 3.6f)
            diamond(12f, 15.2f, 3.6f)
        }.build()
    }
}
