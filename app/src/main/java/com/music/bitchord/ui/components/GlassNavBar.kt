/*
 * The bar's structure and its inline/expanded behaviour are
 * EchoMusicApp/Echo-Music's AppFloatingNavBar + FloatingMiniPlayer (GPL-3.0),
 * over the FloatingTabBar vendored in [com.music.bitchord.ui.components.floatingtabbar].
 * BitChord's own tabs, song model, transport and haptics are wired through it in
 * place of Echo's Screens routing and PlayerConnection.
 */
@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarInlineBehavior
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBar
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarDefaults
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.player.AndroidMusicVideo
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxSize

/**
 * The liquid glass navigation bar: the iOS 26 shape where the now playing
 * controls and the tabs are one component rather than two stacked bars.
 *
 * Expanded, it is a full width now playing pill sitting over the tab pill and a
 * separate circular Search tab. Scrolling down collapses it inline — the tabs
 * fold down to just the selected one, the now playing controls narrow into the
 * gap between it and Search, and the whole thing becomes a single row the width
 * of the screen. Scrolling back up expands it. The fold is driven by
 * [scrollConnection], which the page's scroll has to be dispatched into for any
 * of this to move; see MainActivity's `nestedScroll`.
 *
 * Every surface here samples the app backdrop through [Modifier.liquidGlass], so
 * this is only ever used where that is supported and switched on — off either,
 * MainActivity draws [MiniPlayer] and [FloatingBottomBar] instead.
 *
 * [song] null means nothing is playing, and the accessory is simply absent: the
 * bar is then the tab pill and Search alone, and the collapse still works.
 */
@Composable
fun GlassNavBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    scrollConnection: FloatingTabBarScrollConnection,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    /** @see com.music.bitchord.data.listentogether.ListenTogether.State.controlsLocked */
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    /** Where the player's artwork lands when it closes into this bar. */
    dock: PlayerDock? = null,
    /** Dragging the bar's player up into the full player. */
    pull: MiniPlayerPull? = null,
    modifier: Modifier = Modifier,
) {
    // Held for the same reason [tabs] is. The glass factory below closes over
    // this shape, and a fresh RoundedCornerShape each pass means a fresh factory
    // lambda, which the tab bar sees as a changed argument and recomposes on.
    val pillShape = remember { RoundedCornerShape(percent = 50) }
    val contentColor = glassContentColor()
    val selectedColor = contentColor
    val unselectedColor = contentColor.copy(alpha = 0.65f)
    val haptics = rememberHaptics()

    // The tab content lambdas below are captured once per contentKey and held
    // until it changes, so a click handler that reached back to this call's
    // `onTabSelected` would go stale the moment anything it closes over moved.
    // Held through a state that is always current instead, which also keeps the
    // handler out of the key.
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    // Search: the last cell of the bar while it is expanded, and a circle of its own once it folds (Apple
    // Music's iOS 26 bar). It is the last tab in BitChord's order, which is where the shape wants it anyway.
    val standaloneIndex = tabs.lastIndex

    // A factory, not a value — see the note in FloatingTabBar's header. Each of
    // the three surfaces gets its own glass modifier and so its own shape cache.
    // The theme's own glass tint, as the liquid top bar has it — no white wash.
    val glassSurface: @Composable () -> Modifier = {
        Modifier.liquidGlass(shape = pillShape, surfaceTint = navGlassTint(), rimAlpha = NAV_GLASS_RIM, depth = NAV_GLASS_DEPTH, blur = NAV_GLASS_BLUR)
    }

    // ---- The fold, as Apple Music's: one movement driven by one value -----------------------------
    //
    // [morph] runs from 0, expanded (the now playing bar full width over the tab bar, Search a cell of
    // it), to 1, collapsed (one row: the open tab's circle, the now playing bar, Search's circle). Every
    // piece is placed from it at layout and draw — the tab bar's glass shrinking into the tab's circle,
    // the open tab's glyph travelling into it, Search's glyph leaving its cell for a circle of its own,
    // the now playing bar narrowing first and then dropping (and rising first, then widening, on the
    // way back) — so nothing is handed between two layouts and nothing lands on anything else.
    val compact = scrollConnection.isInline
    // Three movements on Apple Music's own timings, measured frame by frame off its bar (60 fps):
    //  - [fold]: the tab bar folding into the open tab's circle and Search splitting off (under 0.1 s), and
    //    back out of them;
    //  - [narrow]: the now playing bar's width — collapsing it leads (half done in 80 ms, done by 200 ms);
    //    expanding it follows the rise (from 60 ms to 250 ms);
    //  - [drop]: its fall to the collapsed row — after the narrowing has a head start (80 ms to 420 ms) —
    //    and, expanding, its rise, which leads (done by 180 ms).
    // Each on a lightly underdamped spring, as Apple's: it runs a few percent past its mark and settles
    // back — the bar dips a touch below its row and narrows a touch more than it ends up, and rising, it
    // comes up a little over its place and a little wider — which is what makes the glass read as liquid.
    val fold = remember { Animatable(if (compact) 1f else 0f) }
    val narrow = remember { Animatable(if (compact) 1f else 0f) }
    val drop = remember { Animatable(if (compact) 1f else 0f) }
    LaunchedEffect(compact) {
        suspend fun Animatable<Float, *>.go(to: Float, stiffness: Float, delayMs: Long = 0L) {
            if (delayMs > 0L) delay(delayMs)
            animateTo(to, spring(dampingRatio = NAV_SPRING_DAMPING, stiffness = stiffness))
        }
        if (compact) {
            launch { fold.go(1f, FOLD_IN_STIFFNESS) }
            launch { narrow.go(1f, NARROW_IN_STIFFNESS) }
            launch { drop.go(1f, DROP_IN_STIFFNESS, DROP_IN_DELAY_MS) }
        } else {
            launch { drop.go(0f, RISE_STIFFNESS) }
            launch { narrow.go(0f, WIDEN_STIFFNESS, WIDEN_DELAY_MS) }
            launch { fold.go(0f, FOLD_OUT_STIFFNESS, FOLD_OUT_DELAY_MS) }
        }
    }
    val m: () -> Float = { fold.value }
    // The tab the collapsed circle shows: the open one, or the last one before Search was opened.
    var inlineIndex by remember { mutableIntStateOf(selectedIndex.takeIf { it != standaloneIndex } ?: 0) }
    if (selectedIndex != standaloneIndex) inlineIndex = selectedIndex
    val searchSelected = selectedIndex == standaloneIndex

    val density = LocalDensity.current
    val rowPx = with(density) { INLINE_ROW.toPx() }
    val liftPx = with(density) { INLINE_LIFT.toPx() }
    val gutterPx = with(density) { (INLINE_GUTTER - NAV_BAR_GUTTER).toPx() }
    val spacingPx = with(density) { INLINE_SPACING.toPx() }
    val accessoryPx = with(density) { ACCESSORY_HEIGHT.toPx() }
    val gapPx = with(density) { ACCESSORY_GAP.toPx() }
    val hasAccessory = song != null

    // Measured pieces, read at layout and draw only.
    val box = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val iconSpots = remember { HashMap<Int, LayoutCoordinates>() }
    var tabBarHeight by remember { mutableIntStateOf(0) }

    val geometry = remember(density) { NavMorphGeometry() }
    val boxSize = remember { FloatArray(2) }
    fun frame(width: Float, height: Float): NavMorphGeometry = geometry.apply {
        val p = fold.value
        val tabH = tabBarHeight.toFloat().takeIf { it > 0f } ?: rowPx
        tabExpanded = Rect(0f, height - tabH, width, height)
        val rowTop = height - liftPx - rowPx
        home = Rect(gutterPx, rowTop, gutterPx + rowPx, rowTop + rowPx)
        searchCircle = Rect(width - gutterPx - rowPx, rowTop, width - gutterPx, rowTop + rowPx)
        // Not straight across: folding, the bar drops onto the row first and then draws in sideways;
        // unfolding, it spreads sideways first and then rises — a short curve, as Apple's circles take.
        tab = Rect(
            lerpF(tabExpanded.left, home.left, sideways(p)),
            lerpF(tabExpanded.top, home.top, upDown(p)),
            lerpF(tabExpanded.right, home.right, sideways(p)),
            lerpF(tabExpanded.bottom, home.bottom, upDown(p)),
        )
        piecesShown = (p / 0.3f).coerceIn(0f, 1f)
        val accExpanded = Rect(0f, tabExpanded.top - gapPx - accessoryPx, width, tabExpanded.top - gapPx)
        val accCompact = Rect(home.right + spacingPx, rowTop, searchCircle.left - spacingPx, rowTop + rowPx)
        val h = narrow.value
        val v = drop.value
        accessory = Rect(
            lerpF(accExpanded.left, accCompact.left, h),
            lerpF(accExpanded.top, accCompact.top, v),
            lerpF(accExpanded.right, accCompact.right, h),
            lerpF(accExpanded.bottom, accCompact.bottom, v),
        )
        accessoryInline = h >= 0.5f
        searchGlass = p.coerceIn(0f, 1f)
        tabsShown = 1f - (p / 0.5f).coerceIn(0f, 1f)
    }
    fun now(): NavMorphGeometry = frame(boxSize[0], boxSize[1])
    fun heightFor(@Suppress("UNUSED_PARAMETER") unused: Float = 0f): Float {
        // Held within its two heights: the overshoot is the now playing bar's, not the whole bar's.
        val p = drop.value.coerceIn(0f, 1f)
        val tabH = tabBarHeight.toFloat().takeIf { it > 0f } ?: rowPx
        val expanded = tabH + if (hasAccessory) gapPx + accessoryPx else 0f
        val collapsed = rowPx + liftPx
        return lerpF(expanded, collapsed, p)
    }

    val innerScroll = remember { FloatingTabBarScrollConnection(false, Float.MAX_VALUE, FloatingTabBarInlineBehavior.Never) }

    Box(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = NAV_BAR_GUTTER)
            .padding(bottom = NAV_BAR_LIFT)
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height = heightFor(m()).roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                boxSize[0] = placeable.width.toFloat()
                boxSize[1] = height.toFloat()
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .onPlaced { box[0] = it },
    ) {
        // The tab bar's glass, from the whole bar into the open tab's circle and back out of it. At rest
        // expanded the bar's own glass is the bar, so this one comes in only as that one goes.
        Box(
            Modifier
                .placeAt { w, h -> frame(w, h).tab }
                .graphicsLayer { alpha = now().piecesShown }
                .then(glassSurface())
                .clickable(
                    enabled = compact,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    scrollConnection.expand()
                    if (inlineIndex != selectedIndex) currentOnTabSelected(inlineIndex)
                },
        )
        // The expanded bar itself, laid out at its full size where it rests, fading out as its halves take
        // over (and back in as they meet).
        Box(
            Modifier
                .placeAtBottom()
                .onSizeChanged { tabBarHeight = it.height }
                .graphicsLayer {
                    val g = now()
                    alpha = g.tabsShown
                    // Cut down to the shrinking glass, so the bar folds into the circle rather than fading in place.
                    clip = g.tabsShown < 1f
                    shape = RoundRectShape(g.tab.translate(-g.tabExpanded.left, -g.tabExpanded.top))
                },
        ) {
            FloatingTabBar(
                selectedTabKey = selectedIndex,
                scrollConnection = innerScroll,
                modifier = Modifier.fillMaxWidth(),
                tabBarContentModifier = glassSurface,
                colors = FloatingTabBarDefaults.colors(
                    backgroundColor = Color.Transparent,
                    accessoryBackgroundColor = Color.Transparent,
                    indicatorColor = glassIndicatorColor(),
                ),
                elevations = FloatingTabBarDefaults.elevations(inlineElevation = 0.dp, expandedElevation = 0.dp),
                sizes = FloatingTabBarDefaults.sizes(
                    tabBarContentPadding = PaddingValues(PILL_INSET),
                    tabExpandedContentPadding = PaddingValues(vertical = TAB_VERTICAL_PADDING),
                ),
                contentKey = remember(selectedIndex, tabs, contentColor) { listOf(selectedIndex, tabs, contentColor) },
                standaloneInExpandedBar = true,
            ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            val tint = if (isSelected) selectedColor else unselectedColor
            val onClick = {
                if (!isSelected) haptics.play(Haptic.Select)
                currentOnTabSelected(index)
            }
            // The tab that is open wears the accent: a soft gradient over its glyph and its name.
            val accent = if (isSelected) Modifier.navAccent() else Modifier
            // The glyphs that travel in the fold stand aside while their travelling copies are out, and say
            // where they are for those copies to start from.
            val travels = index == standaloneIndex || index == inlineIndex
            val spot = Modifier
                .onPlaced { iconSpots[index] = it }
                .graphicsLayer { alpha = if (travels && m() > 0f) 0f else 1f }
            if (index == standaloneIndex) {
                standaloneTab(
                    key = index,
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(TAB_ICON_SIZE).then(spot).then(accent),
                        )
                    },
                    onClick = onClick,
                    title = {
                        Text(
                            text = tab.label,
                            style = tabLabelStyle(),
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = TAB_ICON_LABEL_GAP).then(accent),
                        )
                    },
                )
            } else {
                tab(
                    key = index,
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(TAB_ICON_SIZE).then(spot).then(accent),
                        )
                    },
                    title = {
                        Text(
                            text = tab.label,
                            style = tabLabelStyle(),
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // The library's Tab stacks the glyph and the label
                            // with nothing between them; the plain bar spaces
                            // them, and this is where that gap goes.
                            modifier = Modifier.padding(top = TAB_ICON_LABEL_GAP).then(accent),
                        )
                    },
                    onClick = onClick,
                )
            }
        }
    }
        }
        // Search's own circle, growing round its glyph as it leaves the bar, and merging back into it.
        Box(
            Modifier
                .placeAt { w, h ->
                    val g = frame(w, h)
                    lerp(Rect(g.searchCircle.center, 0f), g.searchCircle, g.searchGlass)
                }
                .graphicsLayer { alpha = now().searchGlass.coerceIn(0f, 1f) }
                .then(glassSurface())
                .clickable(
                    enabled = compact,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { currentOnTabSelected(standaloneIndex) },
        )
        // The two glyphs that travel: the open tab's, into its circle, and Search's, into its own.
        TravellingGlyph(
            tab = tabs[inlineIndex],
            tint = if (searchSelected) unselectedColor else selectedColor,
            accent = !searchSelected,
            shown = { m() > 0f },
            at = { w, h ->
                val g = frame(w, h)
                val from = iconSpots[inlineIndex]?.let { spot -> centreIn(box[0], spot) } ?: g.tabExpanded.center
                curved(from, g.home.center, m())
            },
        )
        TravellingGlyph(
            tab = tabs[standaloneIndex],
            tint = if (searchSelected) selectedColor else unselectedColor,
            accent = searchSelected,
            shown = { m() > 0f },
            at = { w, h ->
                val g = frame(w, h)
                val from = iconSpots[standaloneIndex]?.let { spot -> centreIn(box[0], spot) } ?: g.searchCircle.center
                curved(from, g.searchCircle.center, m())
            },
        )
        // The now playing bar.
        if (song != null) {
            // In the morph the glass is drawn growing out of here (see [PlayerMorphGlass]), so this bar's own
            // glass stands aside meanwhile; the artwork, the lines and the buttons see to themselves.
            val morphHidesGlass by remember(dock) {
                derivedStateOf { dock != null && dock.morphing() && dock.coversMiniArt }
            }
            // Rising past its place it also swells a touch, and settles back with it.
            Box(
                Modifier
                    .placeAt { w, h -> frame(w, h).accessory }
                    .graphicsLayer {
                        val s = 1f + swell(narrow.value, drop.value) * ACCESSORY_SWELL
                        scaleX = s
                        scaleY = s
                    },
            ) {
                val inline by remember { derivedStateOf { narrow.value >= 0.5f } }
                GlassNowPlaying(
                    song = song,
                    isInline = inline,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    contentColor = contentColor,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onExpand = onExpand,
                    controlsLocked = controlsLocked,
                    onBlockedControl = onBlockedControl,
                    dock = dock,
                    pull = pull,
                    swell = { swell(narrow.value, drop.value) },
                    modifier = Modifier.fillMaxSize().clip(pillShape).then(if (morphHidesGlass) Modifier else glassSurface()),
                )
            }
        }
    }
}

/**
 * The now playing controls docked into [GlassNavBar] as its accessory.
 *
 * Two densities of the same row rather than two components, so the shared
 * element carrying it between the bar's states has one thing to interpolate:
 * [isInline] drops the skip button and draws the play button smaller. The
 * artwork and the type are the same at both, as they are in Apple's accessory,
 * which only changes width between its two placements.
 *
 * The content is [MiniPlayer]'s — same artwork, same transport, same haptics —
 * and not Echo's, which reaches into a player connection this app doesn't have.
 * The press response is Echo's, and belongs to the glass rather than the row:
 * a surface you can push on is the whole point of the material.
 */
@Composable
private fun GlassNowPlaying(
    song: Song,
    isInline: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    dock: PlayerDock?,
    pull: MiniPlayerPull?,
    swell: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val pressSource = remember { MutableInteractionSource() }
    val isPressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 1.04f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "accessoryPressScale",
    )

    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = ACCESSORY_TITLE_SIZE,
        fontWeight = ACCESSORY_TITLE_WEIGHT,
        letterSpacing = ACCESSORY_TITLE_TRACKING,
    )
    val artistStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = ACCESSORY_ARTIST_SIZE,
        letterSpacing = ACCESSORY_ARTIST_TRACKING,
    )
    val glyphSlot = if (isInline) 32.dp else 40.dp
    // The transport fades as the bar opens into the player, and comes back as it closes into the bar.
    val transport = Modifier.graphicsLayer {
        alpha = if (dock != null && dock.morphing() && dock.coversMiniArt) {
            (1f - dock.openFraction() / TRANSPORT_FADE_SPAN).coerceIn(0f, 1f)
        } else {
            1f
        }
    }
    val glyphSize = if (isInline) 24.dp else EXPANDED_GLYPH_SIZE

    Box(
        // Inline, the accessory is stretched to the row's height by the tab bar
        // (`fillMaxHeight` on a row measured at IntrinsicSize.Max), and this row
        // is shorter than that — the tab pill beside it, with a 28dp glyph in
        // 10dp of padding, is the tallest thing in the row and sets the height.
        // A Box defaults to TopStart, so the artwork and the transport sat a
        // couple of dp above the pill's centre line. Expanded the Box wraps its
        // content, so centring is a no-op there.
        contentAlignment = Alignment.Center,
        // The gestures and the tap belong to the whole glass surface, not the
        // row centred in it. Inline, that row is shorter than the pill it sits
        // in, and a pull started in the strip above or below it went nowhere —
        // on a bar only 48dp tall to begin with.
        modifier = Modifier
            // Outside the press scale, which it lifts along with everything else.
            .miniPlayerGestures(
                pull = pull,
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
                locked = controlsLocked,
                onBlocked = onBlockedControl,
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(modifier)
            .playerDockContainer(dock)
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onExpand,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // Coming out of the row the artwork and the controls run a touch out towards the glass's
                // edges, and a touch larger, before they settle — more than the glass itself does.
                .graphicsLayer {
                    val s = 1f + swell() * CONTENT_SWELL
                    scaleX = s
                    scaleY = s
                }
                .padding(
                    // The artwork sits in the end of a capsule, which curves
                    // away from it; at 8dp its corner met the curve.
                    start = 16.dp,
                    end = if (isInline) 8.dp else 12.dp,
                    // Expanded, the 40dp play button and these come to the 48dp
                    // Apple's accessory is.
                    top = 4.dp,
                    bottom = 4.dp,
                ),
        ) {
            // A music video plays in the cover's place at its own 16:9, as in
            // the floating mini player; the full player takes it over when up.
            val showsPicture = song.isVideo && MusicOnlyPlayback.showsPicture(song.videoId)
            val artWidth by animateDpAsState(
                targetValue = if (showsPicture && !VideoThumbnails.square) ACCESSORY_ART_SIZE * (16f / 9f) else ACCESSORY_ART_SIZE,
                label = "glassMiniArtWidth",
            )
            Box(
                modifier = Modifier
                    .size(width = artWidth, height = ACCESSORY_ART_SIZE)
                    // Whichever of the two densities is on screen is the one
                    // the player flies to: only one is composed at rest.
                    .playerDockArt(dock, ACCESSORY_ART_CORNER)
                    .clip(RoundedCornerShape(ACCESSORY_ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = rememberRemoteArtworkUrl(song)?.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showsPicture) {
                    AndroidMusicVideo(
                        videoId = song.videoId,
                        fit = false,
                        onRenderedChanged = {},
                        modifier = Modifier.fillMaxSize(),
                        priority = 0,
                    )
                }
            }
            Spacer(Modifier.width(if (isInline) 8.dp else 9.dp))
            // Title and artist at both densities, as Apple Music has them, in its
            // small, openly spaced type (see [ACCESSORY_TITLE_SIZE]). The inline
            // row shares its width with the tab pill and the Search circle, so
            // a long line fades out at its end, as Apple's does.
            Column(Modifier.weight(1f).playerDockCredits(dock)) {
                val secondary = contentColor.copy(alpha = 0.7f)
                ExplicitSongTitle(
                    song = song,
                    style = titleStyle,
                    color = contentColor,
                    badgeColor = secondary,
                    fadeOverflow = true,
                )
                FadingLine(
                    text = song.artist,
                    style = artistStyle,
                    color = secondary,
                )
            }
            if (isLoading) {
                Box(Modifier.size(glyphSlot).then(transport), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = contentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(if (isInline) 18.dp else 20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        onPlayPause()
                    },
                    modifier = Modifier.size(glyphSlot).then(transport),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = contentColor,
                        modifier = Modifier.size(glyphSize),
                    )
                }
            }
            // Dropped inline: the collapsed row is sharing its width with the
            // tab pill and the Search circle, and the title is what has to
            // survive that, not a second transport button.
            // It comes in from the glass's right edge as the bar widens, and goes back out the same way.
            androidx.compose.animation.AnimatedVisibility(
                visible = !isInline,
                enter = androidx.compose.animation.expandHorizontally(
                    animationSpec = spring(dampingRatio = NAV_SPRING_DAMPING, stiffness = WIDEN_STIFFNESS),
                    expandFrom = Alignment.Start,
                    clip = false,
                ) + androidx.compose.animation.slideInHorizontally(
                    animationSpec = spring(dampingRatio = NAV_SPRING_DAMPING, stiffness = WIDEN_STIFFNESS),
                ) { it } + androidx.compose.animation.fadeIn(tween(160, delayMillis = 40)),
                exit = androidx.compose.animation.shrinkHorizontally(
                    animationSpec = spring(stiffness = NARROW_IN_STIFFNESS),
                    shrinkTowards = Alignment.Start,
                    clip = false,
                ) + androidx.compose.animation.slideOutHorizontally(
                    animationSpec = spring(stiffness = NARROW_IN_STIFFNESS),
                ) { it } + androidx.compose.animation.fadeOut(tween(90)),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                // Glyph centres 44dp apart, as Apple's are.
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    },
                    enabled = !controlsLocked,
                    modifier = Modifier.size(glyphSlot).then(transport),
                ) {
                    MiniPlayerNextGlyph(
                        tint = contentColor.copy(alpha = if (controlsLocked) 0.3f else 1f),
                    )
                }
              }
            }
        }
    }
}

/**
 * The selected tab's accent: what is drawn is kept as a mask and filled with a soft gradient from blue through
 * a hint of blue violet to a very light blue, blue for almost all of it — Apple Music's place for its red, in Auden's colours.
 */
internal fun Modifier.navAccent(): Modifier = this
    .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colorStops = NAV_ACCENT,
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn,
        )
    }

// Blue throughout; a breath of blue violet and a very light blue only at the far corner, never leading.
private val NAV_ACCENT = arrayOf(
    0.00f to Color(0xFF5C9DFF),
    0.55f to Color(0xFF6FAAFF),
    0.80f to Color(0xFF8A9DFF),
    1.00f to Color(0xFFB0DDFF),
)

/** The collapsed row's margin at either side of the screen, and its items' spacing. */
private val INLINE_GUTTER = 28.dp
private val INLINE_SPACING = 14.dp

/**
 * Half the difference between the expanded tab row, 62dp, and the collapsed
 * one, 48 — a 28dp glyph in 10dp of padding — so the one folds onto the
 * other's centre line.
 */
private val INLINE_LIFT = 7.dp

/**
 * The accessory's type, measured off Apple's, collapsed and expanded alike:
 * 13pt over 12pt, set looser than the scale's 16 and 14 as SF Pro Text spaces
 * these sizes — the title less so than measured, tuned by eye on device: in
 * Semibold +0.4 read loose and the cut's own spacing tight.
 */
private val ACCESSORY_TITLE_SIZE = 13.sp

/**
 * W700, which in this app's family draws the Semibold cut (see SFProDisplay):
 * Apple's accessory sets its title in Semibold, and the scale's W600 — the
 * Medium cut — read thin at 13sp.
 */
private val ACCESSORY_TITLE_WEIGHT = FontWeight.W700
private val ACCESSORY_TITLE_TRACKING = 0.15.sp
private val ACCESSORY_ARTIST_SIZE = 12.sp
private val ACCESSORY_ARTIST_TRACKING = 0.35.sp

/** The accessory's artwork, about Apple's 30pt at both densities, and its corner. */
private val ACCESSORY_ART_SIZE = 30.4.dp
private val ACCESSORY_ART_CORNER = 5.7.dp

/** The expanded play glyph: its triangle about Apple's 15pt tall. */
private val EXPANDED_GLYPH_SIZE = 29.dp

/** One frame of the fold, worked out from its single value — see [GlassNavBar]. */
private class NavMorphGeometry {
    var tabExpanded = Rect.Zero
    var home = Rect.Zero
    var searchCircle = Rect.Zero
    var tab = Rect.Zero
    var accessory = Rect.Zero
    var accessoryInline = false
    var searchGlass = 0f
    var tabsShown = 1f
    var leftPiece = Rect.Zero
    var rightPiece = Rect.Zero
    var piecesShown = 0f
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpO(a: Offset, b: Offset, t: Float) = Offset(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t))

/** The centre of [spot] in [box]'s pixels, or null before both are placed. */
private fun centreIn(box: LayoutCoordinates?, spot: LayoutCoordinates): Offset? {
    val parent = box?.takeIf { it.isAttached } ?: return null
    if (!spot.isAttached) return null
    return parent.localPositionOf(spot, Offset(spot.size.width / 2f, spot.size.height / 2f))
}

/** A pill (round ends) filling [rect]. */
private class RoundRectShape(private val rect: Rect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(minOf(rect.width, rect.height) / 2f)))
}

/**
 * Sized and placed in the parent at the rect [at] gives this frame, from the parent's size — at layout,
 * so a frame of the fold relays out these pieces and recomposes nothing. [sizeOf] sizes it differently
 * from where it is placed, when the two differ.
 */
private fun Modifier.placeAt(at: (Float, Float) -> Rect): Modifier = layout { measurable, constraints ->
    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    val rect = at(w, h)
    val placeable = measurable.measure(
        Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
}

/** Full width, its own height, standing on the parent's bottom edge: where the expanded tab bar rests. */
private fun Modifier.placeAtBottom(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth),
    )
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(0, constraints.maxHeight - placeable.height)
    }
}

/** A tab glyph carried through the fold on its own. */
@Composable
private fun BoxScope.TravellingGlyph(
    tab: BottomTab,
    tint: Color,
    accent: Boolean,
    shown: () -> Boolean,
    at: (Float, Float) -> Offset,
) {
    val half = with(LocalDensity.current) { (TAB_ICON_SIZE / 2).roundToPx() }
    Icon(
        imageVector = tab.icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(half * 2, half * 2))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val c = at(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                    placeable.place(c.x.roundToInt() - half, c.y.roundToInt() - half)
                }
            }
            .graphicsLayer { alpha = if (shown()) 1f else 0f }
            .then(if (accent) Modifier.navAccent() else Modifier),
    )
}

/**
 * Apple Music's fold, on springs — see [GlassNavBar]. A damping of 0.74 runs about 3% past the mark and back,
 * the small soft settle of Apple's glass; the stiffnesses keep its measured pacing (the fold well under 0.1 s
 * collapsing, the bar narrowing by ~200 ms and dropping after an 80 ms head start; rising first, then widening).
 * Unfolding, the tab bar spreading out of its circle is slower than it was, so it does not snap open.
 */
private const val NAV_SPRING_DAMPING = 0.74f
private const val FOLD_IN_STIFFNESS = 900f
private const val NARROW_IN_STIFFNESS = 600f
private const val DROP_IN_STIFFNESS = 340f
private const val DROP_IN_DELAY_MS = 80L
private const val RISE_STIFFNESS = 520f
private const val WIDEN_STIFFNESS = 300f
private const val WIDEN_DELAY_MS = 60L
private const val FOLD_OUT_STIFFNESS = 260f
private const val FOLD_OUT_DELAY_MS = 30L

/** How much the now playing glass, and its contents beyond that, swell per unit of overshoot rising. */
private const val ACCESSORY_SWELL = 0.6f
private const val CONTENT_SWELL = 1.4f

/** How far past its rest the bar is, rising: its overshoot above its place and out past its width. */
private fun swell(narrow: Float, drop: Float): Float = maxOf(0f, -narrow, -drop)

/** The fold's sideways share: behind folding (drop first), ahead unfolding (spread first). */
private fun sideways(p: Float): Float = if (p in 0f..1f) p * (0.55f + 0.45f * p) else p

/** The fold's up and down share: the mirror of [sideways]. */
private fun upDown(p: Float): Float = 1f - sideways(1f - p)

/** From [a] to [b] on the fold's short curve rather than straight across. */
private fun curved(a: Offset, b: Offset, p: Float) = Offset(lerpF(a.x, b.x, sideways(p)), lerpF(a.y, b.y, upDown(p)))

/**
 * A stretch of the fold's value, eased within its span — and past either end, carried on as it is, so the
 * spring's overshoot reaches the shapes instead of being cut off as a hard stop.
 */
private fun phase(p: Float, start: Float, span: Float): Float = when {
    p > 1f -> 1f + (p - 1f) / span
    p < 0f -> p / span
    else -> FastOutSlowInEasing.transform(((p - start) / span).coerceIn(0f, 1f))
}

private fun eased(p: Float): Float = phase(p, 0f, 1f)
private val INLINE_ROW = 48.dp
private val ACCESSORY_HEIGHT = 48.dp
private val ACCESSORY_GAP = 8.dp

/**
 * The mini player's glass becoming the player: drawn in the app's window, under the player's, from the bar to
 * the whole of the player while it opens and back while it closes (see [com.music.bitchord.ui.player.PlayerDock]).
 * The glass keeps sampling the page the whole way; the title and artist travel inside it from the bar's lines to
 * the player's; the artwork is the player's own, flying above. The player comes through only at the end.
 *
 * [fraction] is the morph's, 1 open and 0 on the bar, or null when no morph is on screen; [playerRect] where the
 * player stands on screen this frame.
 */
@Composable
fun PlayerMorphGlass(
    dock: PlayerDock,
    song: Song?,
    glass: Boolean,
    fraction: () -> Float?,
    playerRect: () -> Rect?,
    modifier: Modifier = Modifier,
) {
    val t = fraction() ?: return
    val bar = dock.miniContainerOnScreen() ?: return
    val full = playerRect() ?: return
    val density = LocalDensity.current
    val origin = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val at = origin[0]?.takeIf { it.isAttached }?.positionOnScreen() ?: Offset.Zero
    var screenHeight by remember { mutableIntStateOf(0) }
    // The card in this layer's pixels: its top is the player's top (the player rides it), its sides and its
    // floor as [com.music.bitchord.morphCard] has them.
    val local = bar.translate(-at.x, -at.y)
    val k = t.coerceIn(0f, 1f)
    val top = full.top - at.y
    val floor = screenHeight.toFloat().takeIf { it > 0f } ?: (full.bottom - at.y)
    val f = (k / 0.3f).coerceIn(0f, 1f)
    val bottom = local.bottom + (floor - local.bottom) * smooth(f)
    val rect = Rect(local.left * (1f - k), top, local.right + ((full.right - at.x) - local.right) * k, bottom)
    val radius = with(density) {
        val sheet = MORPH_SHEET_CORNER.toPx()
        val half = bar.height / 2f
        when {
            k < 0.3f -> half + (sheet - half) * (k / 0.3f)
            k > 0.85f -> sheet * (1f - (k - 0.85f) / 0.15f)
            else -> sheet
        }.toDp()
    }
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .fillMaxSize()
            .onPlaced { origin[0] = it; screenHeight = it.size.height }
            .drawBehind { drawRect(Color.Black.copy(alpha = MORPH_SCRIM_ALPHA * k)) },
    ) {
        Box(
            Modifier
                .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                // Only at about the bar's size: the glass is the bar the card starts from and ends as, never a
                // big grey card of its own (which read as a flash at the end of a close).
                .graphicsLayer { alpha = 1f - smooth((k / GLASS_SPAN).coerceIn(0f, 1f)) }
                .then(
                    if (glass && isGlassSupported()) {
                        Modifier.liquidGlass(shape = shape)
                    } else {
                        Modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
                    },
                ),
        )
        // The mini player's lines, riding the card's top edge and fading as the player comes through (Apple
        // Music's mini row at the top of the card), and back in at the end of a close.
        val mini = dock.miniCreditsOnScreen()
        val shown = 1f - smooth((k / GLASS_SPAN).coerceIn(0f, 1f))
        if (song != null && mini != null && shown > 0f) {
            val left = mini.left - at.x
            val y = mini.top - at.y + (top - local.top)
            Column(
                Modifier
                    .offset { IntOffset(left.roundToInt(), y.roundToInt()) }
                    .width(with(density) { mini.width.toDp() })
                    .graphicsLayer { alpha = shown },
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = ACCESSORY_TITLE_SIZE,
                        fontWeight = ACCESSORY_TITLE_WEIGHT,
                        letterSpacing = ACCESSORY_TITLE_TRACKING,
                    ),
                    color = glassContentColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = ACCESSORY_ARTIST_SIZE,
                        letterSpacing = ACCESSORY_ARTIST_TRACKING,
                    ),
                    color = glassContentColor().copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** How much of the morph the bar's glass and lines last: while the card is still about the bar's size. */
private const val GLASS_SPAN = 0.14f

private val MORPH_SHEET_CORNER = 40.dp
private const val TRANSPORT_FADE_SPAN = 0.25f
private const val MORPH_SCRIM_ALPHA = 0.35f
