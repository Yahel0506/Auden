package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.DjMeshPalettes
import com.music.bitchord.ui.player.DjRing
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.cyclingMeshPalette

/**
 * The DJ's cover while it speaks: its mesh of colours drifting and moving on, and the ring of its visualizer
 * breathing and talking over it — the DJ's player in a few dp. What the DJ's voice is shown as in the mini player
 * and the glass bar, in the cover's place; the notification and the lock screen get the same thing as a picture
 * ([dev.auden.dj.DjCoverArt]).
 */
@Composable
fun DjCover(modifier: Modifier = Modifier) {
    Box(modifier) {
        MeshGradientBackground(
            palette = cyclingMeshPalette(DjMeshPalettes, stepMillis = 2_400),
            modifier = Modifier.fillMaxSize(),
            continuous = true,
            driftMillis = 3_200,
            // A small surface: the four colours are smeared less, or they blend into one before the edges.
            blurRadius = 10.dp,
            colorFadeMillis = 2_000,
        )
        DjRing(level = { 0f }, modifier = Modifier.fillMaxSize(), talking = true)
    }
}
