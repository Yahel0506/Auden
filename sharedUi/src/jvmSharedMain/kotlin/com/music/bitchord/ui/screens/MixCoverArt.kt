package com.music.bitchord.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.cyclingMeshPalette
import kotlinx.coroutines.delay

private val LoopPalettes = listOf(
    MeshPalette(listOf(Color(0xFFFF7A59), Color(0xFFFFC36B), Color(0xFFFF5C8A), Color(0xFFB04BFF))),
    MeshPalette(listOf(Color(0xFFFFB24B), Color(0xFFFF5C8A), Color(0xFFFF7A59), Color(0xFFFFD86B))),
)
private val GenrePalettes = listOf(
    MeshPalette(listOf(Color(0xFF14B8A6), Color(0xFF2F7BFF), Color(0xFF6FE3C0), Color(0xFF8A4BFF))),
    MeshPalette(listOf(Color(0xFF2F7BFF), Color(0xFF6FE3C0), Color(0xFF14B8A6), Color(0xFF4FD22C))),
)
private val NewPalettes = listOf(
    MeshPalette(listOf(Color(0xFF8A4BFF), Color(0xFF2F7BFF), Color(0xFFFF7FB8), Color(0xFF6FC8FF))),
    MeshPalette(listOf(Color(0xFFFF7FB8), Color(0xFF8A4BFF), Color(0xFF6FC8FF), Color(0xFF2F7BFF))),
)
private val BecausePalettes = listOf(
    MeshPalette(listOf(Color(0xFFF0364F), Color(0xFFFF8A4B), Color(0xFFB04BFF), Color(0xFFFF7FB8))),
    MeshPalette(listOf(Color(0xFFFF7FB8), Color(0xFFF0364F), Color(0xFFFFB27A), Color(0xFF8A4BFF))),
)

private fun TopPickKind.palettes() = when (this) {
    TopPickKind.IN_LOOP -> LoopPalettes
    TopPickKind.GENRE_MIX -> GenrePalettes
    TopPickKind.NEW_FOR_YOU -> NewPalettes
    else -> BecausePalettes
}

/** The small line above a generated list's title: its colour, matched to its backdrop. */
fun TopPickKind.mixEyebrow(): Color = when (this) {
    TopPickKind.IN_LOOP -> Color(0xFFFFE0C2)
    TopPickKind.GENRE_MIX -> Color(0xFFC8FFF2)
    TopPickKind.NEW_FOR_YOU -> Color(0xFFE2D2FF)
    else -> Color(0xFFFFD2DC)
}

/**
 * The cover of one of the lists Auden makes each day: a mesh of colour drifting behind four sleeves from the list
 * that trade places one after another. The same cover on the big card on Home and at the head of the list's own page,
 * so opening it does not change what it looks like. Held still by "reduce animation" (the mesh checks) and when
 * [motion] is false.
 *
 * With [captionSpace] the sleeves keep to the upper part and the foot is darkened for the card's caption; without it
 * (the page's cover) they are centred on the whole square.
 */
@Composable
fun MixCoverArt(
    kind: TopPickKind,
    images: List<String>,
    modifier: Modifier = Modifier,
    motion: Boolean = true,
    captionSpace: Boolean = true,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion, images.size) {
        if (!motion || images.size < 2) return@LaunchedEffect
        while (true) {
            delay(3_200)
            tick++
        }
    }
    Box(modifier.background(Color.Black)) {
        MeshGradientBackground(
            palette = cyclingMeshPalette(kind.palettes(), running = motion),
            modifier = Modifier.matchParentSize(),
            continuous = true,
            paused = !motion,
            driftMillis = 7_000,
            blurRadius = 30.dp,
        )
        BoxWithConstraints(Modifier.matchParentSize()) {
            val w = maxWidth
            val h = maxHeight
            val tile = minOf(w * 0.34f, h * (if (captionSpace) 0.30f else 0.34f))
            val gap = tile * 0.12f
            val gridW = tile * 2 + gap
            val left = (w - gridW) / 2
            val top = if (captionSpace) h * 0.10f else (h - gridW) / 2
            val tilts = listOf(-4f, 3f, 3f, -3f)
            (0 until 4).forEach { slot ->
                val url = images.takeIf { it.isNotEmpty() }
                Box(
                    Modifier
                        .offset(x = left + (tile + gap) * (slot % 2), y = top + (tile + gap) * (slot / 2))
                        .size(tile)
                        .graphicsLayer { rotationZ = tilts[slot] }
                        .shadow(8.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .border(BorderStroke(0.8.dp, Color.White.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    if (url != null) {
                        Crossfade(targetState = (tick + slot * 2) % url.size, animationSpec = tween(900), label = "mix-sleeve") { index ->
                            AsyncImage(
                                model = url[index],
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        if (captionSpace) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
        }
    }
}
