package com.music.bitchord.ui.welcome

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer

/**
 * A square, silent video that plays [intro] once if there is one and then [loop] for good.
 *
 * The loop is not a repeat of one item, which restarts the decoder at every pass and shows as a hitch: each time
 * a pass begins, the next is queued behind it in the same playlist, so the player runs on from one into the next
 * without a gap.
 */
@OptIn(UnstableApi::class)
@Composable
fun WelcomeVideo(intro: Int?, loop: Int, play: Boolean, modifier: Modifier = Modifier, square: Boolean = true) {
    val context = LocalContext.current
    fun item(res: Int) = MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(res))
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            if (intro != null) addMediaItem(item(intro))
            addMediaItem(item(loop))
            addMediaItem(item(loop))
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // Always one pass queued ahead of the one playing.
                    if (currentMediaItemIndex >= mediaItemCount - 1 || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        addMediaItem(item(loop))
                    }
                    // Played items are dropped so the playlist does not grow for as long as the page is open.
                    if (currentMediaItemIndex > 2) removeMediaItems(0, currentMediaItemIndex - 1)
                }
            })
            prepare()
            playWhenReady = false
        }
    }
    // Held on its first frame until [play]: the intro starts from second 0 only once the screen is the user's.
    androidx.compose.runtime.LaunchedEffect(play) { player.playWhenReady = play }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = if (square) modifier.fillMaxWidth().aspectRatio(1f) else modifier,
    )
}
