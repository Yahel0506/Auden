package com.music.bitchord.ui.splash

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.R
import kotlinx.coroutines.delay

/** The colour the light video's floor measures, for the moment before its first frame is in. */
val SplashLightBackground = Color(0xFFF4F5F8)

/**
 * The launch splash: the logo's liquid-glass animation, in the light or the dark cut, played once over the app as it
 * opens. It stands on the colour the video itself is made on, so it reads as the screen and not as a clip in a frame,
 * and it takes touches while it is up so nothing underneath is pressed by accident.
 *
 * It gives way when the video ends, with a short fade, or after [MAX_MS] if the video never starts (a device with no
 * decoder for it): the app should never be held behind a splash that cannot play.
 */
@OptIn(UnstableApi::class)
@Composable
fun LaunchSplash(dark: Boolean, onStatic: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val finished = rememberUpdatedState(onDone)
    val staticReached = rememberUpdatedState(onStatic)
    val fade = remember { Animatable(1f) }
    val ended = remember { androidx.compose.runtime.mutableStateOf(false) }
    val renderedFirst = remember { androidx.compose.runtime.mutableStateOf(false) }

    // The splash stands on the colour the video is actually *drawn* in on this screen, read off its first frame: what a
    // decoder and a display make of the file is not quite the number in it, and a page colour chosen from the number
    // showed as a faint frame round the video. Until that frame is in, the colour the file measures.
    var floor by remember { androidx.compose.runtime.mutableStateOf(if (dark) Color.Black else SplashLightBackground) }
    val textureView = remember { androidx.compose.runtime.mutableStateOf<TextureView?>(null) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(
                MediaItem.Builder()
                    .setUri(RawResourceDataSource.buildRawResourceUri(if (dark) R.raw.splash_dark else R.raw.splash_light))
                    // The file stands still for its first 120 ms and after 1.3 s: play only the motion between.
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(CLIP_START_MS)
                            .setEndPositionMs(CLIP_END_MS)
                            .build(),
                    )
                    .build(),
            )
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFirst.value = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended.value = true
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    ended.value = true
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Whichever comes first: the video's end, or the longest the splash may hold the app.
    LaunchedEffect(Unit) {
        delay(MAX_MS)
        ended.value = true
    }
    // From [STATIC_AT_MS] the picture is only the logo standing still: nothing on screen can hitch, so this is when the
    // app is allowed to start composing underneath. Until then the video has the device to itself and runs smooth.
    LaunchedEffect(player) {
        while (!ended.value && player.currentPosition < STATIC_AT_MS) delay(50)
        staticReached.value()
    }
    LaunchedEffect(renderedFirst.value) {
        if (!renderedFirst.value) return@LaunchedEffect
        delay(60)
        // Scaled down to a few pixels, so a corner is the average of the floor round it, not one grain of it.
        val sample = textureView.value?.getBitmap(8, 8) ?: return@LaunchedEffect
        floor = Color(sample.getPixel(0, 0))
        sample.recycle()
    }
    LaunchedEffect(ended.value) {
        if (!ended.value) return@LaunchedEffect
        // The app composes underneath from [STATIC_AT_MS] and that can hold a frame for a while: let it finish, so the
        // dissolve below runs on frames that are free instead of being eaten by it.
        repeat(4) { androidx.compose.runtime.withFrameNanos { } }
        fade.animateTo(0f, tween(SPLASH_EXIT_MS, easing = androidx.compose.animation.core.CubicBezierEasing(0.5f, 0f, 1f, 0.6f)))
        finished.value()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = fade.value
                // Zooming in on the logo: it rushes at the viewer while the screen clears behind it.
                val k = 1f + 5f * (1f - fade.value)
                scaleX = k
                scaleY = k
            }
            .background(floor)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // The video is square: as wide as the screen allows, never taller than it.
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it); textureView.value = it } },
            modifier = Modifier
                .align(Alignment.Center)
                .size(side)
                // The video's floor is a hair off a flat colour (it is a compressed picture), and its square would
                // show as a faint frame on a tall screen: its four edges fade into the page colour instead.
                .drawWithContent {
                    drawContent()
                    val edge = size.minDimension * FEATHER
                    for ((from, to) in listOf(
                        Offset.Zero to Offset(0f, edge),
                        Offset(0f, size.height) to Offset(0f, size.height - edge),
                        Offset.Zero to Offset(edge, 0f),
                        Offset(size.width, 0f) to Offset(size.width - edge, 0f),
                    )) {
                        drawRect(Brush.linearGradient(listOf(floor, Color.Transparent), start = from, end = to))
                    }
                },
        )
    }
}

const val SPLASH_EXIT_MS = 320
private const val MAX_MS = 6_000L

/** Where the logo has landed and the picture stops moving. */
private const val CLIP_START_MS = 120L
private const val CLIP_END_MS = 1_320L
private const val STATIC_AT_MS = 1_100L

/** The share of the video's side its edges fade over. */
private const val FEATHER = 0.08f
