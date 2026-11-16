package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "A newer version", as a solid disc with a fat arrow cut out of it: round and filled enough to sit with the top
 * bar's other controls, where the thin outlined arrow it replaces looked lost beside them.
 */
object UpdateIcon {
    val Filled: ImageVector by lazy {
        ImageVector.Builder(
            name = "update_filled",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(
                // The disc.
                "M12 1.5C17.8 1.5 22.5 6.2 22.5 12C22.5 17.8 17.8 22.5 12 22.5C6.2 22.5 1.5 17.8 1.5 12C1.5 6.2 6.2 1.5 12 1.5Z" +
                    // The arrow, cut out: a wide head and a thick shaft.
                    "M12 5.6C12.4 5.6 12.8 5.8 13.1 6.1L17.3 10.3C17.9 10.9 17.9 11.8 17.3 12.4C16.7 13 15.8 13 15.2 12.4L13.5 10.7V17.1C13.5 17.9 12.8 18.6 12 18.6C11.2 18.6 10.5 17.9 10.5 17.1V10.7L8.8 12.4C8.2 13 7.3 13 6.7 12.4C6.1 11.8 6.1 10.9 6.7 10.3L10.9 6.1C11.2 5.8 11.6 5.6 12 5.6Z",
            ),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ).build()
    }
}
