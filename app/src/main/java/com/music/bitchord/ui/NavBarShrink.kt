package com.music.bitchord.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection

/**
 * How much the expanded bar is shrunk, 0 to 1, for a layer block to read. While it is expanded that is how far the
 * scroll has taken it toward folding; when it folds, it is where the fold starts from — the bar goes on from the size it
 * had got to, and the shrinking is given back as the fold plays, not all at once before it.
 */
@Composable
fun rememberNavBarShrink(scroll: FloatingTabBarScrollConnection): () -> Float {
    val decay = remember { Animatable(1f) }
    LaunchedEffect(scroll.isInline) {
        if (scroll.isInline) {
            // The fold's own pace (see GlassNavBar).
            decay.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 900f))
        } else {
            // Ready for the next fold, which starts from the full amount.
            decay.snapTo(1f)
        }
    }
    return { if (scroll.isInline) decay.value.coerceIn(0f, 1f) * scroll.foldedFrom else scroll.progress }
}
