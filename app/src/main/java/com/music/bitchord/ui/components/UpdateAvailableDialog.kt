package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** UIAlertController's own metrics: fixed narrow width, 14pt corner, 44pt rows. */
internal val ALERT_WIDTH = 270.dp
internal val ALERT_CORNER = 34.dp
internal val ACTION_HEIGHT = 44.dp

/**
 * The dim behind the alert. Flat on purpose — the glass is the card, and
 * blurring the wallpaper *behind* it too leaves nothing for the card to be
 * frosted against, which is what made this read as a grey box before.
 */
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)

private val DOWNLOAD_ROW_HEIGHT = 4.dp

/** How much of the card's height the release notes are allowed to fill before scrolling. */
private val NOTES_MAX_HEIGHT = 220.dp

/**
 * Full-bleed action row. Tinted rather than filled, so the two read as equals
 * in weight and only the font differentiates the default action — the alert's
 * whole point is that neither choice is a trap.
 *
 * [destructive] is the one exception to that: an action that deletes something
 * is red in this lineage, because the point there *is* to break the symmetry.
 * It stays a plain row rather than a filled button, so it reads as a warning
 * and not as the thing to press.
 */
@Composable
internal fun AlertAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    /** Overrides the action's colour, for the one that has to be the strongest red. */
    color: Color? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    // iOS 26's alert: each answer its own capsule. The one to press is filled in the accent (red for one that deletes),
    // the others sit on a soft grey and carry the colour in their label.
    val accent = accentColor()
    val filled = emphasised
    val fill = when {
        filled -> color ?: if (destructive) scheme.error else accent
        else -> scheme.onSurface.copy(alpha = 0.12f)
    }
    // The label on the fill is whichever of black and white holds against it: a white accent takes a black one.
    val textColor = when {
        filled -> if (fill.luminance() > 0.55f) Color.Black else Color.White
        else -> color ?: if (destructive) scheme.error else scheme.onSurface
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ALERT_ACTION_INSET, vertical = 3.dp)
            .height(ACTION_HEIGHT + 2.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(fill.copy(alpha = fill.alpha * if (enabled) 1f else 0.4f))
            // A press lightens or darkens the capsule instead of drawing a ripple in it.
            .background(if (pressed) Color.Black.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W500,
            ),
            color = textColor.copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}

/** How far the capsules sit in from the card's sides, and the room left under the last one. */
internal val ALERT_ACTION_INSET = 14.dp
internal val ALERT_ACTIONS_BOTTOM = 11.dp

/** What stood between two actions as a hairline: nothing, now that each is a capsule with its own room. */
@Composable
internal fun AlertGap() {
    Spacer(Modifier.height(0.dp))
}

/** Hairline separator — [HorizontalDivider][androidx.compose.material3.HorizontalDivider]'s 1dp reads as a bar at this scale. */
@Composable
internal fun AlertRule(modifier: Modifier = Modifier, start: Dp = 20.dp, end: Dp = 20.dp) {
    // Starts and ends inside the card, as far from its sides on one as on the other, so it never reaches its rounded edge.
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = start, end = end)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}
