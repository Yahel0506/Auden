package com.music.bitchord.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.dj_ask
import com.music.bitchord.sharedui.resources.dj_loading
import com.music.bitchord.sharedui.resources.dj_no_words
import com.music.bitchord.sharedui.resources.dj_title
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.theme.StatusBarIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The player while the DJ speaks: the main player, with the DJ as its cover. The same moving mesh behind
 * it as behind any song, the same layout — cover, title and what is said, the bar, the transport, the
 * bottom row with the DJ's button where the queue's would be — and the DJ's face on the cover, which
 * opens its mouth with the voice (read off the loudness shape computed when the line was rendered) and
 * rests when it pauses. What is said is written beneath, so nothing depends on hearing it.
 */
@Composable
fun DjNowPlayingScreen(
    /** The line being said, as written; null when none is known. */
    words: String?,
    /** What the current set is about, shown under the DJ's name. */
    theme: String?,
    /** The loudness of the line over its length, 0..1. Empty when there is none. */
    envelope: FloatArray,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
    onAsk: () -> Unit,
    /** The DJ is choosing: the request is in and its first words are not yet. */
    loading: Boolean = false,
    /** A hold on the DJ's button: leave the choice to the DJ. */
    onSurprise: (() -> Unit)? = null,
    /** A line of small print under the words, for what went wrong (the voice, mostly); null for none. */
    note: String? = null,
) {
    // Over the mesh, as over any song: light icons in the bar.
    StatusBarIcons(dark = false)
    val level = rememberVoiceLevel(
        envelope = envelope,
        positionMs = { position.positionMs + if (isPlaying) (System.nanoTime() - position.sampledAtNanos) / 1_000_000L else 0L },
        durationMs = durationMs,
        isPlaying = isPlaying,
        loading = loading,
    )
    Box(Modifier.fillMaxSize()) {
        // The player's own backdrop, never at rest: the mesh drifts and its colours move on every few seconds.
        MeshGradientBackground(
            palette = cyclingMeshPalette(DjMeshPalettes),
            modifier = Modifier.fillMaxSize(),
            continuous = true,
            driftMillis = 6_000,
            blurRadius = 56.dp,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = PLAYER_GUTTER),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))
            // Who this is, quietly, at the top.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.dj_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(18.dp))
            // The voice: one ring.
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                DjRing(level = { level.value }, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (theme != null) {
                    Text(
                        text = theme,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.6.sp,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // While it chooses, the line breathes.
                val pulse by rememberInfiniteTransition(label = "djLoading").animateFloat(
                    initialValue = 0.5f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
                    label = "djLoadingPulse",
                )
                Text(
                    text = when {
                        loading -> stringResource(Res.string.dj_loading)
                        else -> words ?: stringResource(Res.string.dj_no_words)
                    },
                    modifier = Modifier.graphicsLayer { alpha = if (loading) pulse else 1f },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.W600,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // The same transport as the player's. Back has nothing to step to, so it rests.
            TransportRow(
                isPlaying = isPlaying,
                isLoading = false,
                previousEnabled = false,
                nextEnabled = !loading,
                onPrevious = {},
                onPlayPause = onPlayPause,
                onNext = onSkip,
            )
            Spacer(Modifier.height(26.dp))
            // Where the queue's button is in the player, the DJ's: the way to ask for something else.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                BottomGlyph(
                    icon = Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(Res.string.dj_ask),
                    onClick = onAsk,
                    onLongClick = onSurprise,
                    modifier = Modifier.menuAnchor(),
                )
            }
        }
    }
}

