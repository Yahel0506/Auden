package com.music.bitchord.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.welcome.WelcomeVideo

/**
 * The sign-in card's background: the welcome's loop of drops, filling the card edge to edge (the video is square, so
 * its middle band is what shows), with the navbar's glass edge drawn round it.
 */
@Composable
fun SignInBannerBackground(modifier: Modifier, motion: Boolean) {
    val shape = RoundedCornerShape(22.dp)
    BoxWithConstraints(modifier.clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
        WelcomeVideo(
            intro = null, loop = R.raw.welcome_drops_loop, play = motion,
            modifier = Modifier.requiredSize(maxWidth), square = false,
        )
        Box(Modifier.matchParentSize().liquidGlassRim(shape).border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape))
    }
}
