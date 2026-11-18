package com.music.bitchord.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What the bars do while a screen is stacked on the account sheet's page: they are that screen's, so they come in with
 * it and go as it leaves, and are never drawn over the sheet's page abruptly. Kept out of the app's body, which is as
 * large as a method can be.
 */
@Stable
class AccountSheetStack {
    val chromeAlpha = Animatable(1f)

    /**
     * True from the moment a screen is stacked until the sheet is back on top after it is taken off: read in the same
     * composition that takes it off, so the sheet is not moved from under the leaving screen to over it and back.
     */
    var wasStacked by mutableStateOf(false)

    /** False until the fade-in has begun, so the bars are not drawn for the frame before it does. */
    var chromeStarted by mutableStateOf(false)
}

@Composable
fun rememberAccountSheetStack(stacked: Boolean): AccountSheetStack {
    val state = remember { AccountSheetStack() }
    LaunchedEffect(stacked) {
        if (stacked) {
            state.wasStacked = true
            state.chromeAlpha.snapTo(0f)
            state.chromeStarted = true
            state.chromeAlpha.animateTo(1f, tween(260, delayMillis = 90))
        } else if (state.wasStacked) {
            state.chromeStarted = false
            state.chromeAlpha.animateTo(0f, tween(150))
            // The leaving screen's slide is done by now.
            delay(230)
            state.wasStacked = false
            // Back in, gradually: it is under the sheet but for its top edge, and a jump there is seen.
            state.chromeAlpha.animateTo(1f, tween(260))
        }
    }
    return state
}
