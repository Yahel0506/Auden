package com.music.bitchord.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A page area whose transition follows the back gesture: the page on top is dragged away and the one under it shows
 * as it goes. Let go before the edge and the page comes back; finish the gesture and [onBack] makes the step.
 */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun rememberDestinationState(now: AppDestination): SeekableTransitionState<AppDestination> {
    val state = remember { SeekableTransitionState(now) }
    LaunchedEffect(now) { state.animateTo(now) }
    return state
}

/** Where one step back leaves the page area, worked out the way [AppNavigator.pop] will do it. */
internal fun destinationAfterBack(
    entries: List<RouteEntry>,
    releaseIds: List<String>,
    tab: Int,
): AppDestination {
    fun at(list: List<RouteEntry>, depth: Int): AppDestination {
        val entry = list.lastOrNull()?.takeIf { depth <= it.detailDepth && it.route !is AppRoute.ArtistShelf }
        return when {
            entry != null -> AppDestination.Route(entry)
            depth > 0 -> AppDestination.Release(releaseIds[depth - 1])
            else -> AppDestination.Tab(tab)
        }
    }
    val top = entries.lastOrNull()
    return if (releaseIds.size > (top?.detailDepth ?: 0)) at(entries, releaseIds.size - 1) else at(entries.dropLast(1), releaseIds.size)
}

/**
 * True from the first frame of a back gesture until it has been settled one way or the other. The page area reads it
 * when it picks the transition a gesture scrubs: whichever pair of pages is involved, the gesture slides the page on top
 * away over the one under it — a crossfade would draw the page being returned to over the one being left.
 */
@Volatile
internal var predictiveBackInFlight: Boolean = false

@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun PredictivePageBack(
    enabled: Boolean,
    state: SeekableTransitionState<AppDestination>,
    current: AppDestination,
    target: () -> AppDestination,
    onBack: () -> Unit,
) {
    val now by rememberUpdatedState(current)
    PredictiveBackHandler(enabled = enabled) { progress ->
        val to = target()
        val seekable = to.key != now.key
        predictiveBackInFlight = seekable
        var committed = false
        var last = 0f
        try {
            progress.collect { event ->
                last = event.progress
                if (seekable) state.seekTo(event.progress, to)
            }
            committed = true
            onBack()
            if (seekable) {
                // The page area animates on by itself once the step has changed what is on top (see
                // [rememberDestinationState]). If the step left it where it was, nothing will, and the scrub would
                // stay where the finger let go: look again once the new state has surely been composed, and if the
                // transition is heading anywhere but where the area is, send it there — back the way it came.
                withFrameNanos { }
                withFrameNanos { }
                if (state.targetState.key != now.key) withContext(NonCancellable) { state.animateTo(now) }
            }
        } catch (e: CancellationException) {
            // Let go before the edge. This coroutine is already cancelled, so the way back has to run outside it or
            // it never runs at all and the two pages are left half-way, both on screen and both reachable.
            // Only if the step was not taken: once it has been, the page area is on its way to the page it opens.
            if (seekable && !committed) withContext(NonCancellable) { state.settleBack(last, to, state.currentState) }
            throw e
        } finally {
            predictiveBackInFlight = false
        }
    }
}

/** The same for a screen that slides in over another and is not a destination: [state] is whether it is shown. */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun rememberShownState(shown: Boolean): SeekableTransitionState<Boolean> {
    val state = remember { SeekableTransitionState(shown) }
    LaunchedEffect(shown) { state.animateTo(shown) }
    return state
}

@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun PredictiveSlideBack(enabled: Boolean, state: SeekableTransitionState<Boolean>, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) { progress ->
        var last = 0f
        try {
            progress.collect { event ->
                last = event.progress
                state.seekTo(event.progress, false)
            }
            onBack()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { state.settleBack(last, false, true) }
            throw e
        }
    }
}

/**
 * Brings a scrub that was let go of early back to where it started: the fraction runs down to nothing at one steady
 * pace and the area is then set to [home]. Driven here, frame by frame, rather than handed to the transition to
 * animate, which runs each page's own enter and exit again from where the finger left them and so plays the way back
 * a second time.
 */
@OptIn(ExperimentalTransitionApi::class)
private suspend fun <T> SeekableTransitionState<T>.settleBack(from: Float, scrubbedTo: T, home: T) {
    if (from > 0f) {
        coroutineScope {
            val fraction = Animatable(from)
            val run = launch { fraction.animateTo(0f, spring(dampingRatio = 1f, stiffness = 300f)) }
            while (run.isActive) {
                seekTo(fraction.value, scrubbedTo)
                withFrameNanos { }
            }
        }
    }
    snapTo(home)
}

private var zTicker = 0f

/**
 * The stacking of the page a transition brings in, over every page that has been brought in before it.
 *
 * AnimatedContent remembers the stacking each page was given the last time it was the one coming in, and a page that
 * is going out keeps it. A fixed 1 for a push and -1 for a pop therefore tied as soon as a page that had been returned
 * to (-1) was itself left by a back gesture (to -1 again): a tie is settled by which page was composed last, which is
 * the one being returned to — drawn over the one being left. Counting up from every earlier value, in either
 * direction, never ties.
 */
internal fun pushZ(): Float { zTicker += 1f; return zTicker }

/** The stacking of the page a pop returns to: under every page seen so far, see [pushZ]. */
internal fun backZ(): Float { zTicker += 1f; return -zTicker }
