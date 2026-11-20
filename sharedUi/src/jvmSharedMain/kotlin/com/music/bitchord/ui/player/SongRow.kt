package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/*
 * The songs in a row, the way Mochy Music switches songs (SongCarousel.kt there): the playing song,
 * the one before it to the left and the one after it to the right. A sideways swipe slides the row
 * under the finger one to one and letting go carries the flick's speed into the settle; a skip from
 * anywhere else (the buttons, the song ending, the queue) runs the row along the same way.
 *
 * Covers and titles read their place from this one row, so they move as one. Where the row is
 * counts in steps, one per song moved through, and never resets: a song changing in the middle of a
 * slide changes nothing on screen, the slot that was coming in is now the one in the middle and the
 * row just carries on to it.
 */

/** One song in the row, [step] its place in it. */
@Stable
internal class RowSong(val step: Int, val song: Song)

// how fast the row settles: critically damped like a picture pager, no bounce, a flick's speed carried straight in
private const val SettleOmega = 18f

// a frame longer than this counts as this long, so a hitch pauses the row for a moment instead of making it jump
private const val MaxFrameSec = 1f / 40f

// at rest once it is within half a pixel and barely moving
private const val RestDistance = 0.0005f
private const val RestSpeed = 0.01f

// a skip the player never confirms stops holding the row on its song after this long
private const val SkipConfirmMs = 1_200L

// let go past half a song and it moves on; short of that it eases back, unless it was flicked
private const val SwipeCommit = 0.5f

// past the first or last song the row only gives this share of the finger's travel
private const val EdgeGive = 0.3f

// text travels this share of its own width per song and is gone by two thirds of the way
private const val TextTravel = 0.75f
private const val TextFade = 1.5f

/** How far a cover drifts slower than its page, which is what reads as depth. */
internal const val RowParallax = 0.3f

@Stable
internal class SongRow(private val scope: CoroutineScope) {
    /** Where the row is, in steps. At rest it is [step]. Read while drawing or placing only. */
    var position by mutableFloatStateOf(0f)
        private set

    /** The playing song's step: one more for each song forward, one less for each back. */
    var step by mutableIntStateOf(0)
        private set

    /** The songs in the row, left to right: the one before, the playing one, the one after. */
    var slots by mutableStateOf<List<RowSong>>(emptyList())
        private set

    /** True from a finger taking the row until it lets go. */
    var dragging by mutableStateOf(false)
        private set

    /** Songs [slot] is from the middle: 0 in place, +1 a whole song to the right. */
    fun offsetOf(slot: RowSong): Float = slot.step - position

    /** Songs the playing one is from the middle. */
    val playingOffset: Float get() = step - position

    /** Set by the player: how much colour the covers keep, less while paused. Read while drawing only. */
    var saturation: () -> Float = { 1f }

    /** Set by the player: how far a full-bleed cover is zoomed inside its frame, less once paused. Read while drawing only. */
    var zoom: () -> Float = { 1f }

    /** Set by the player: how a committed swipe changes the song. */
    var skip: (forward: Boolean) -> Unit = {}

    // ---- the queue ----

    private var playingId: String? = null
    private var previousId: String? = null
    private var nextId: String? = null
    private var queueIndex = -1

    /**
     * The queue as composition sees it. When the playing song changes, the row moves one step
     * toward wherever the new song was in it. Idempotent, so composing twice changes nothing.
     */
    fun onQueue(song: Song, queue: List<Song>, index: Int) {
        val inQueue = index in queue.indices && queue[index].videoId == song.videoId
        val last = playingId
        if (last != null && last != song.videoId) {
            // a skip that was waiting on this is confirmed
            expectedStep = null
            confirmJob?.cancel()
            val delta = when (song.videoId) {
                nextId -> 1
                previousId -> -1
                else -> if (inQueue && index < queueIndex) -1 else 1
            }
            step += delta
        }
        playingId = song.videoId
        if (inQueue) {
            queueIndex = index
            previousId = queue.getOrNull(index - 1)?.videoId
            nextId = queue.getOrNull(index + 1)?.videoId
        } else {
            queueIndex = -1
            previousId = null
            nextId = null
        }
        val row = buildList {
            if (inQueue) queue.getOrNull(index - 1)?.let { add(RowSong(step - 1, it)) }
            add(RowSong(step, song))
            if (inQueue) queue.getOrNull(index + 1)?.let { add(RowSong(step + 1, it)) }
        }
        if (row.size != slots.size || row.indices.any { row[it].step != slots[it].step || row[it].song != slots[it].song }) {
            slots = row
        }
    }

    // ---- moving the row ----

    private var job: Job? = null
    private var velocity = 0f // songs per second, + toward the next one
    private var headingTo = Float.NaN

    // the step a skip that has gone will land on, until the queue says it has (so nothing glides back meanwhile)
    private var expectedStep: Int? = null
    private var confirmJob: Job? = null

    private fun restingStep(): Int = expectedStep ?: step

    /** Slides the row to the playing song, carrying on with whatever speed it has. */
    fun settle() {
        if (dragging) return
        glideTo(restingStep())
    }

    private fun expect(target: Int) {
        expectedStep = target
        confirmJob?.cancel()
        confirmJob = scope.launch {
            delay(SkipConfirmMs)
            // the player never moved on: back to the song that is playing
            expectedStep = null
            settle()
        }
    }

    // one critically damped spring, stepped per frame with long frames capped, so the row never jumps
    private fun glideTo(target: Int) {
        val to = target.toFloat()
        // already on its way there: a restart would only be the same motion again
        if (headingTo == to && job?.isActive == true) return
        job?.cancel()
        headingTo = to
        job = scope.launch {
            var lastNanos = 0L
            var resting = false
            while (!resting) {
                resting = withFrameNanos { now ->
                    val dt = if (lastNanos == 0L) 1f / 120f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, MaxFrameSec)
                    lastNanos = now
                    val y = position - to
                    val e = exp(-SettleOmega * dt)
                    val tmp = (velocity + SettleOmega * y) * dt
                    val next = (y + tmp) * e
                    velocity = (velocity - SettleOmega * tmp) * e
                    position = to + next
                    abs(next) < RestDistance && abs(velocity) < RestSpeed
                }
            }
            position = to
            velocity = 0f
            headingTo = Float.NaN
            if (restingStep() != target) {
                // the song did not change after all: back to the one playing
                glideTo(restingStep())
            }
        }
    }

    private var dragFrom = 0f

    // a finger touched the row: a gliding row stops dead under it, like any scrolling picture
    private fun hold() {
        job?.cancel()
        headingTo = Float.NaN
        velocity = 0f
    }

    /** A finger took the row; from here it sits exactly under the finger. */
    fun startDrag() {
        if (dragging) return
        hold()
        dragFrom = position
        dragging = true
    }

    /**
     * The finger is [travelledPx] from where it went down, on something [spacingPx] wide per song.
     * Past the queue's ends the row only gives a little.
     */
    fun dragTo(travelledPx: Float, spacingPx: Float, hasPrevious: Boolean, hasNext: Boolean) {
        if (!dragging || spacingPx <= 0f) return
        val base = restingStep()
        val raw = dragFrom - travelledPx / spacingPx
        val low = minOf(base - (if (hasPrevious) 1f else 0f), dragFrom)
        val high = maxOf(base + (if (hasNext) 1f else 0f), dragFrom)
        position = when {
            raw < low -> low - (low - raw) * EdgeGive
            raw > high -> high + (raw - high) * EdgeGive
            else -> raw
        }
    }

    /**
     * The finger lifted: past half a song or flicked, the row moves on one song with the flick's
     * speed, otherwise it eases back. Returns +1 or -1 when it changed the song, 0 when it did not.
     */
    fun release(travelledPx: Float, velocityPx: Float, spacingPx: Float, flingPx: Float, hasPrevious: Boolean, hasNext: Boolean): Int {
        if (!dragging) return 0
        dragging = false
        val spacing = spacingPx.coerceAtLeast(1f)
        val base = restingStep()
        // in songs, + toward the next one, measured from the song the row belongs on
        val moved = position - base
        val speed = -velocityPx / spacing
        val fling = flingPx / spacing
        val forward = (moved > SwipeCommit || speed > fling) && speed > -fling && hasNext
        val back = !forward && (moved < -SwipeCommit || speed < -fling) && speed < fling && hasPrevious
        val delta = when {
            forward -> 1
            back -> -1
            else -> 0
        }
        // the finger's own speed carries straight into the settle, so letting go never jolts
        velocity = speed
        if (delta != 0) {
            expect(base + delta)
            skip(delta > 0)
        }
        glideTo(base + delta)
        return delta
    }

    /** The finger was taken away by something else: back to the song it belongs on. */
    fun cancelDrag() {
        if (!dragging) return
        dragging = false
        velocity = 0f
        glideTo(restingStep())
    }
}

/** The player's row, kept in step with its queue. */
@Composable
internal fun rememberSongRow(song: Song, queue: List<Song>, queueIndex: Int): SongRow {
    val scope = rememberCoroutineScope()
    val row = remember(scope) { SongRow(scope) }
    // in composition, so the slots move with the song in the very frame it changes
    row.onQueue(song, queue, queueIndex)
    LaunchedEffect(row, row.step) { row.settle() }
    return row
}

/**
 * The slots worth drawing text for: the playing song, and a neighbour while it is on its way in or
 * out. Changes only when one joins or leaves, not on every frame of the slide.
 */
@Composable
internal fun rememberTextSlots(row: SongRow): List<RowSong> {
    val shown by remember(row) {
        derivedStateOf { row.slots.filter { it.step == row.step || abs(row.offsetOf(it)) < 0.999f } }
    }
    return shown
}

/**
 * A line of text riding the row: it travels part of its own width per song and is gone by about
 * two thirds of the way, so the leaving and arriving lines never sit on top of each other.
 */
internal fun Modifier.rowText(row: SongRow, slot: RowSong): Modifier = graphicsLayer {
    val o = row.offsetOf(slot)
    translationX = o * size.width * TextTravel
    alpha = (1f - abs(o) * TextFade).coerceIn(0f, 1f)
}

/**
 * A page of the row: it slides a whole width per song, clipped to its own part, and the picture
 * inside drifts a little slower than the page ([RowParallax]), which is what reads as depth.
 * Off the row's visible part it is kept, not drawn.
 */
@Composable
internal fun RowPage(
    row: SongRow,
    slot: RowSong,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(pictureModifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val o = row.offsetOf(slot)
                alpha = if (abs(o) >= 1f) 0f else 1f
                translationX = o * size.width
                clip = true
                val sat = row.saturation()
                colorFilter = if (sat < 0.999f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(sat) }) else null
            },
    ) {
        content(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = -row.offsetOf(slot) * size.width * RowParallax
                    val zoom = row.zoom()
                    scaleX = zoom
                    scaleY = zoom
                },
        )
    }
}

/** A neighbour's cover, loaded the way the playing one is so skipping onto it finds it already in memory. */
@Composable
internal fun RowNeighbourArt(song: Song, modifier: Modifier = Modifier) {
    val art = rememberPlayerArtwork(rememberRemoteArtworkUrl(song))
    AsyncImage(
        model = art.request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = art::onState,
        modifier = modifier,
    )
}
