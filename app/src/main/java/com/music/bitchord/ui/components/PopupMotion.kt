package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How a popup comes and goes: the scrim fades, the card grows from a little under its size with a soft spring
 * and fades in; on the way out it shrinks and fades faster than it came, then the real dismissal runs. One
 * [progress] drives both, 0 hidden and 1 shown.
 *
 * A popup is removed by whoever owns its state, which happens the instant [exit]'s callback runs — so every
 * way of closing it that the popup itself controls (the scrim, back, a button that only closes) goes through
 * [exit] and the callback is what finally removes it.
 */
class PopupMotion(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    private var leaving = false

    suspend fun enter() {
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium))
    }

    /** Plays the way out, then runs [after]. A second call while leaving is ignored. */
    fun exit(after: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            progress.animateTo(0f, tween(durationMillis = 150, easing = FastOutLinearInEasing))
            after()
            // The action did not remove the popup (it was not a closing one): bring it back.
            delay(120)
            leaving = false
            enter()
        }
    }
}

@Composable
fun rememberPopupMotion(): PopupMotion {
    val scope = rememberCoroutineScope()
    val motion = remember { PopupMotion(scope) }
    LaunchedEffect(motion) { motion.enter() }
    return motion
}

/** What a closing action runs through: plays the exit, then the action. Null outside a popup that animates. */
val LocalPopupExit = compositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** [action] — which closes the popup — behind the exit animation, when there is one. */
@Composable
fun animatedExit(action: () -> Unit): () -> Unit {
    val exit = LocalPopupExit.current ?: return action
    return { exit(action) }
}

fun Modifier.popupScrim(motion: PopupMotion, color: Color): Modifier = drawBehind {
    drawRect(color.copy(alpha = color.alpha * motion.progress.value.coerceIn(0f, 1f)))
}

fun Modifier.popupCard(
    motion: PopupMotion,
    from: Float = 0.88f,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier = graphicsLayer {
    val p = motion.progress.value
    val scale = from + (1f - from) * p
    scaleX = scale
    scaleY = scale
    alpha = p.coerceIn(0f, 1f)
    transformOrigin = origin
}

/** The scrim and the box a popup sits in: fades with [motion], closes through it on a tap or back. */
@Composable
fun PopupBox(
    motion: PopupMotion,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrim: Color = SCRIM_COLOR,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler { motion.exit(onDismiss) }
    CompositionLocalProvider(LocalPopupExit provides motion::exit) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .popupScrim(motion, scrim)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { motion.exit(onDismiss) },
                ),
            contentAlignment = alignment,
            content = content,
        )
    }
}
