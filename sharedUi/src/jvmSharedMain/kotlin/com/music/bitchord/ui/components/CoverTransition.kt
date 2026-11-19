@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The scopes a cover is shared across pages with: the page host provides them around each page, and a cover that
 * wants to travel — the card a playlist is opened from, the sleeve it opens onto — asks for them.
 */
class CoverTransitionScopes(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

val LocalCoverTransition = compositionLocalOf<CoverTransitionScopes?> { null }

/**
 * The card whose cover is travelling — the one a finger last went down on — named by its list and by where on the page it
 * stands ([cardCoverKey]): the same list can be on a page more than once (pinned, and again among its own shelf) and
 * only the card that was touched takes part. The name is stable when a page is built again, so a cover that is going
 * back finds the card it left from.
 */
object CoverTransitionTarget {
    var key: String? by mutableStateOf(null)
}

/** Who has taken part in the cover transition on a page, by name: two cards of one name (the same shelf twice) cannot both. */
class CoverClaims {
    private val owners = HashMap<String, Any>()

    fun claim(id: String, token: Any): Boolean = owners.getOrPut(id) { token } === token

    fun release(id: String, token: Any) {
        if (owners[id] === token) owners.remove(id)
    }
}

val LocalCoverClaims = compositionLocalOf<CoverClaims?> { null }

/** The name a card goes by in the cover transition: its list, and the part of the page it stands in. */
fun cardCoverKey(id: String, section: String): String = id.removePrefix("VL") + "|" + section

/** A card's own token, to claim the transition with. */
@Composable
fun rememberCoverToken(): Any = remember { Any() }

/** The cover of a card: shared with the page it opens, when this is the card that was touched. */
@Composable
fun Modifier.cardCoverSharedElement(id: String?, section: String, token: Any): Modifier {
    if (id == null) return this
    val key = cardCoverKey(id, section)
    if (CoverTransitionTarget.key != key) return this
    val claims = LocalCoverClaims.current ?: return this
    androidx.compose.runtime.DisposableEffect(key, token) { onDispose { claims.release(key, token) } }
    return if (claims.claim(key, token)) coverSharedElement(id) else this
}

/** The cover of a page — the sleeve at the head of a release or an artist — shared with the card it was opened from. */
@Composable
fun Modifier.coverSharedElement(id: String?): Modifier {
    val scopes = LocalCoverTransition.current
    if (id == null || scopes == null) return this
    return with(scopes.shared) {
        this@coverSharedElement.sharedElement(
            sharedContentState = rememberSharedContentState(key = "cover:${id.removePrefix("VL")}"),
            animatedVisibilityScope = scopes.visibility,
            // The account sheet's curve, a little quicker.
            boundsTransform = { _, _ -> spring(dampingRatio = 1f, stiffness = 400f) },
        )
    }
}
