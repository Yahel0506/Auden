package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * Lays its content out as tall as it would be with no keyboard up, while taking only the room it was
 * given.
 *
 * A Material 3 modal sheet pads its whole content by the keyboard, so a sheet that is a full-screen
 * player squeezes up into what is left. This undoes that for what is inside: it is measured with the
 * keyboard's height added back and placed from the top, so it is laid out exactly as it was, and the
 * part the keyboard covers is simply under the keyboard. The keyboard's height is read raw
 * ([WindowInsets.ime] is not reduced by what a parent consumed), so it is the one figure that is
 * added back.
 */
@Composable
fun Modifier.keepHeightUnderKeyboard(): Modifier {
    val ime = WindowInsets.ime
    return layout { measurable, constraints ->
    val extra = if (constraints.hasBoundedHeight) ime.getBottom(this) else 0
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = if (constraints.hasBoundedHeight) constraints.minHeight + extra else constraints.minHeight,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight,
        ),
    )
    layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, 0) }
    }
}
