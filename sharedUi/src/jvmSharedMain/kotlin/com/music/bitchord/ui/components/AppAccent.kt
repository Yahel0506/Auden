package com.music.bitchord.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The colours the app can wear as its accent: the navigation bar's open tab, the Library's row icons, the mark on
 * the list that is playing. [nav] is the navigation bar's own step of it — flat, a shade deeper than the lead tone.
 */
enum class AccentChoice(val key: String, val color: Color, val nav: Color = color) {
    BLUE("blue", Color(0xFF0A84FF), Color(0xFF4A8BEE)),
    RED("red", Color(0xFFFC3C53)),
    GREEN("green", Color(0xFF03BA9E)),
    LIME("lime", Color(0xFFB5D609)),
    YELLOW("yellow", Color(0xFFFECA36)),
    ORANGE("orange", Color(0xFFFD7F66)),
    PINK("pink", Color(0xFFF770AA)),
    MAGENTA("magenta", Color(0xFFDB69DB)),
    VIOLET("violet", Color(0xFF9F5BFC)),
    WHITE("white", Color(0xFFFFFFFF));

    companion object {
        fun fromKey(key: String?): AccentChoice = entries.firstOrNull { it.key == key } ?: BLUE
    }
}

/** The accent in use. Set from the saved setting at start and whenever the user picks another. */
object AppAccent {
    private val _choice = MutableStateFlow(AccentChoice.BLUE)
    val choice: StateFlow<AccentChoice> = _choice.asStateFlow()

    fun set(choice: AccentChoice) {
        _choice.value = choice
    }
}

/** Whether the app is showing its light theme. */
@Composable
private fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** [choice] as drawn: white would vanish on a light page, so it is black there. */
@Composable
fun AccentChoice.shown(): Color = if (this == AccentChoice.WHITE && isLightTheme()) Color.Black else color

/** The accent colour, re-read when the user picks another. */
@Composable
fun accentColor(): Color {
    val choice by AppAccent.choice.collectAsStateWithLifecycle()
    return choice.shown()
}

/** The navigation bar's step of the accent. */
@Composable
fun navAccentColor(): Color {
    val choice by AppAccent.choice.collectAsStateWithLifecycle()
    return if (choice == AccentChoice.WHITE) choice.shown() else choice.nav
}
