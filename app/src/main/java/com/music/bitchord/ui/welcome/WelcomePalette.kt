package com.music.bitchord.ui.welcome

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.music.bitchord.R

/**
 * What the screens that carry the logo's drops — the welcome, the update page, the sign-in card — are made of: black
 * with white type and the dark clips in the dark theme, and in the light theme the clips' own pale grey with ink type.
 * The page stands on the colour the clip's edges are, so the square clip does not show as a square.
 */
class WelcomePalette(val light: Boolean) {
    val bg: Color = if (light) Color(0xFFE1E0E3) else Color.Black
    val fg: Color = if (light) Color(0xFF0B0F18) else Color.White
    val intro: Int = if (light) R.raw.welcome_intro_light else R.raw.welcome_intro
    val logoLoop: Int = if (light) R.raw.welcome_logo_loop_light else R.raw.welcome_logo_loop
    val dropsLoop: Int = if (light) R.raw.welcome_drops_loop_light else R.raw.welcome_drops_loop
}

@Composable
fun welcomePalette(): WelcomePalette {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return remember(light) { WelcomePalette(light) }
}
