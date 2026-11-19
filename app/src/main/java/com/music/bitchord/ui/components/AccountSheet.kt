package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.R
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The account sheet the profile button opens, in the manner of Apple Music's: a sheet over the page with its title
 * and a close button, and a few cards of rows on a grouped background — the account and its profile, then where the
 * app's own things are (statistics, listening together), then the settings in the accent.
 *
 * Drawn in the page's own composition and not as a window of its own, so the alert the profile row opens is drawn
 * over it. Held composed while it plays its exit.
 */
@Composable
fun AccountSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    accountName: String?,
    accountPhoto: String?,
    signedIn: Boolean,
    /** The profile in use, shown at the end of its row. */
    activeProfileName: String?,
    /** The party's code while in one, shown at the end of its row. */
    partyCode: String?,
    onAccount: () -> Unit,
    onProfiles: () -> Unit,
    onStatistics: () -> Unit,
    onListenTogether: () -> Unit,
    onSettings: () -> Unit,
    /** The version, as shown in its block: "v2.0.0". */
    version: String,
    onAbout: () -> Unit,
    /** Drawn over the sheet — the profile alert — with the sheet itself as what its glass blurs. */
    overlay: @Composable () -> Unit = {},
    /**
     * How far up the sheet is, 0 to 1. Held by the caller: the sheet is composed inside the page it stands over, and
     * when another screen is stacked on that page and later taken off it, the sheet comes back as it was left.
     */
    progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    /** False while a screen is stacked on the sheet: back then belongs to that screen. */
    backEnabled: Boolean = true,
) {
    LaunchedEffect(open) {
        // A spring, as a sheet's is on the platform: it leaves fast and settles without a visible end, and put away it
        // takes the same way back, a little quicker.
        progress.animateTo(
            if (open) 1f else 0f,
            spring(dampingRatio = 1f, stiffness = if (open) 300f else 420f, visibilityThreshold = 0.0005f),
        )
    }
    if (!open && progress.value <= 0.001f) return

    val scope = rememberCoroutineScope()
    var dragPx by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = open && backEnabled, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetColor = if (dark) Color(0xFF111113) else Color(0xFFF2F2F7)
    val cardColor = if (dark) Color(0xFF1E1E21) else Color.White
    val accent = accentColor()
    val onSurface = if (dark) Color.White else Color.Black
    val secondary = onSurface.copy(alpha = 0.55f)
    val rule = onSurface.copy(alpha = if (dark) 0.14f else 0.12f)

    // What the glass drawn over the sheet blurs is the sheet, recorded here, and not the page alone, which the app's own
    // recording holds. Nothing is painted under it: the page it stands over is the real one.
    val sheetBackdrop = rememberLayerBackdrop()
    val sheetOffset: androidx.compose.ui.unit.Density.() -> IntOffset = {
        IntOffset(0, (((1f - progress.value.coerceIn(0f, 1f)) * heightPx) + dragPx.coerceAtLeast(0f)).roundToInt())
    }

    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().layerBackdrop(sheetBackdrop)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f * progress.value.coerceIn(0f, 1f) * (1f - (dragPx / heightPx.coerceAtLeast(1f)).coerceIn(0f, 1f))))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 12.dp)
                .onSizeChanged { heightPx = it.height.toFloat() }
                .offset(sheetOffset)
                .clip(RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp))
                .background(sheetColor)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
        ) {
            // The title row carries the drag that lets the sheet go.
            Row(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragPx += it },
                        onDragStopped = { velocity ->
                            if (dragPx > 140f || velocity > 1400f) {
                                // Let go: on from where the finger left it, off the screen, at the speed it had; only
                                // then is the sheet put away. Put away first, it went back to the top and closed from there.
                                scope.launch {
                                    val away = Animatable(dragPx)
                                    away.animateTo(
                                        targetValue = heightPx,
                                        animationSpec = spring(dampingRatio = 1f, stiffness = 420f),
                                        initialVelocity = velocity.coerceAtLeast(0f),
                                    ) { dragPx = value }
                                    progress.snapTo(0f)
                                    dragPx = 0f
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    val back = Animatable(dragPx)
                                    back.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f)) { dragPx = value }
                                }
                            }
                        },
                    )
                    .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    tint = onSurface,
                    modifier = Modifier.size(width = 20.dp, height = 23.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.auden_account),
                    modifier = Modifier.weight(1f),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Where the close button stands: it is drawn over the sheet (below), in glass.
                Spacer(Modifier.size(46.dp))
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // The account, and the profile in use.
                SheetCard(cardColor) {
                    Row(
                        Modifier.sheetRow(onSurface, onAccount).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (accountPhoto != null && signedIn) {
                            AsyncImage(
                                model = accountPhoto,
                                contentDescription = null,
                                modifier = Modifier.size(58.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                Modifier.size(58.dp).clip(CircleShape).background(onSurface.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(SfIcons.Person, null, tint = secondary, modifier = Modifier.size(28.dp)) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                accountName?.takeIf { signedIn && it.isNotBlank() } ?: stringResource(R.string.not_signed_in),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.account_sheet_account_sub),
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = secondary,
                            )
                        }
                        Chevron(secondary)
                    }
                    if (signedIn) {
                        Rule(rule, start = 16.dp)
                        Row(
                            Modifier.sheetRow(onSurface, onProfiles).padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.switch_account),
                                modifier = Modifier.weight(1f),
                                fontSize = 17.sp,
                                color = onSurface,
                            )
                            activeProfileName?.let {
                                Text(it, fontSize = 15.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Chevron(secondary)
                        }
                    }
                }

                SheetCard(cardColor) {
                    SheetTextRow(SfIcons.ShowChart, stringResource(R.string.playback_statistics), null, onSurface, secondary, onStatistics)
                    Rule(rule, start = 56.dp)
                    SheetTextRow(SfIcons.PartyPopper, stringResource(R.string.listen_together), partyCode, onSurface, secondary, onListenTogether)
                }

                // The version in force, large, and the way to what it brings.
                SheetCard(cardColor) {
                    Row(
                        Modifier.sheetRow(onSurface, onAbout).padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.about_this_version), fontSize = 17.sp, color = onSurface)
                            Text(
                                version,
                                fontSize = 44.sp,
                                lineHeight = 50.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurface,
                                maxLines = 1,
                            )
                        }
                        Chevron(secondary)
                    }
                }

                SheetCard(cardColor) {
                    Row(
                        Modifier.sheetRow(onSurface, onSettings).padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(SfIcons.Settings, null, tint = accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.auden_settings), fontSize = 17.sp, color = accent)
                    }
                }
            }
        }
      }
      // Over the recorded sheet, with the sheet as what their glass blurs.
      CompositionLocalProvider(LocalAppBackdrop provides sheetBackdrop) {
          // The close button, as the back button is: a circle of the nav bar's glass, with a cross where its arrow is.
          Box(
              Modifier
                  .align(Alignment.TopEnd)
                  .statusBarsPadding()
                  .padding(top = 30.dp, end = 16.dp)
                  .offset(sheetOffset)
                  .size(46.dp)
                  .navGlass(CircleShape)
                  .clip(CircleShape)
                  .clickable(onClick = onDismiss),
              contentAlignment = Alignment.Center,
          ) {
              Icon(SfIcons.Close, stringResource(R.string.close), tint = glassContentColor(), modifier = Modifier.size(18.dp))
          }
          overlay()
      }
    }
}

@Composable
private fun SheetCard(color: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(color)) { content() }
}

@Composable
private fun Rule(color: Color, start: Dp) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = 16.dp).height(0.5.dp).background(color))
}

@Composable
private fun Chevron(tint: Color) {
    Icon(SfIcons.ChevronRight, null, tint = tint, modifier = Modifier.size(14.dp))
}

@Composable
private fun SheetTextRow(icon: ImageVector, label: String, value: String?, onSurface: Color, secondary: Color, onClick: () -> Unit) {
    Row(
        Modifier.sheetRow(onSurface, onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 17.sp, color = onSurface)
        value?.let {
            Text(it, fontSize = 15.sp, color = secondary)
            Spacer(Modifier.width(8.dp))
        }
        Chevron(secondary)
    }
}

/** A row of a card: washed over while pressed, as the alerts' answers are, and no ripple. */
@Composable
private fun Modifier.sheetRow(onSurface: Color, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .fillMaxWidth()
        .background(if (pressed) onSurface.copy(alpha = 0.08f) else Color.Transparent)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}
