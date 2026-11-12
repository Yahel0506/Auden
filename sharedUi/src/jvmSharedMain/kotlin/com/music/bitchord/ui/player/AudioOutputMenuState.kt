package com.music.bitchord.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** What the audio output menu hands back to the player once it has closed. */
enum class AudioOutputAction {
    /** The audio pipeline's own dialog. */
    PIPELINE,

    /** The receiver picker. */
    CAST,

    /** Listen Together: who is in the party, or the page to start or join one. */
    PARTY,
}

/**
 * The audio output menu as the host draws it: a popover over the player, hung off the output button.
 *
 * The host — which owns the glass — draws the menu; the player owns what the menu leads to. This is the
 * hand-off between the two: the player opens it ([open], [anchor]), the host closes it by asking for
 * something ([request]) and the player does that once the menu is gone.
 */
@Stable
class AudioOutputMenuState {
    /** The menu is wanted. */
    var open by mutableStateOf(false)

    /** The button it hangs off, in window coordinates, or null. */
    var anchor: Rect? = null

    /** Something the menu asked for, until the player has taken it up. */
    var pending by mutableStateOf<AudioOutputAction?>(null)
        internal set

    /** Closes the menu and asks the player for [action]. */
    fun request(action: AudioOutputAction) {
        open = false
        pending = action
    }

    internal fun consume() {
        pending = null
    }
}
