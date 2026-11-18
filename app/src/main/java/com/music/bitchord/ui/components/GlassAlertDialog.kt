package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

/**
 * The app's alert for a window of its own — the place of Material's AlertDialog, in the look of the alerts drawn
 * in the page ([AlertScaffold]): the nav bar's glass at the tint's own strength, a centred title and message, the
 * answers stacked full width under capsules.
 *
 * A window of its own cannot be blurred by Haze, so the glass here samples the app's recorded backdrop as the rest of
 * the glass does.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)
    val motion = rememberPopupMotion()
    Dialog(
        onDismissRequest = { motion.exit(onDismissRequest) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalPopupExit provides motion::exit) {
            // The whole window, so the card's place in it is its place on the screen and the glass samples the
            // right part of the page.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { motion.exit(onDismissRequest) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = modifier
                        .width(ALERT_WIDTH)
                        .popupCard(motion)
                        .then(
                            if (reduceDynamicBlur || !isGlassSupported()) {
                                Modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
                            } else {
                                Modifier.alertGlass(shape)
                            },
                        )
                        .clip(shape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ).padding(bottom = ALERT_ACTIONS_BOTTOM),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 19.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        title?.let {
                            ProvideTextStyle(
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.W600,
                                    textAlign = TextAlign.Center,
                                ),
                            ) { it() }
                        }
                        text?.let {
                            Box(Modifier.padding(top = if (title != null) 6.dp else 0.dp)) {
                                ProvideTextStyle(
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp,
                                        textAlign = TextAlign.Center,
                                    ),
                                ) { it() }
                            }
                        }
                    }
                    // Each answer its own capsule, as [AlertAction] draws them: the one to press tinted in the accent, the other
                    // on a soft grey. The buttons themselves are the caller's.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ALERT_ACTION_INSET, vertical = 3.dp)
                            .clip(CircleShape)
                            .background(accentColor().copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) { confirmButton() }
                    dismissButton?.let {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ALERT_ACTION_INSET, vertical = 3.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) { it() }
                    }
                }
            }
        }
    }
}
