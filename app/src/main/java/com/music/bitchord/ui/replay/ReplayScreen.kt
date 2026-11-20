package com.music.bitchord.ui.replay

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.components.PopupBox
import com.music.bitchord.ui.components.alertGlass
import com.music.bitchord.ui.components.animatedExit
import com.music.bitchord.ui.components.popupCard
import com.music.bitchord.ui.components.rememberPopupMotion
import com.music.bitchord.ui.player.rememberArtworkColors
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.lerp
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

/** Whether the Replay page is being shown in dark mode (vs light). */
@Composable
private fun isDarkMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

private val ReplayRed = Color(0xFFFF3B30)
private val ReplayOrange = Color(0xFFFF8A00)
private val ReplayYellow = Color(0xFFFFC31A)

/**
 * The Replay page, in the shape of Apple Music's: a slow red-orange-yellow
 * glow that melts into the page, a big sentence about the minutes, then the
 * top artists, songs, albums and milestones of the chosen week, month, year or
 * all of it.
 *
 * The glow is three radial gradients drifting on one slow loop and drawn in the
 * draw phase only — no blur, no layers, nothing recomposed per frame.
 */
@Composable
fun ReplayScreen(
    state: ReplayState,
    /** The signed-in account name embossed on the Replay cards. */
    holder: String,
    onPeriodChange: (ReplayPeriod, Int) -> Unit,
    onOpenStory: (ReplayStoryPage) -> Unit,
    onPlaySong: (Song) -> Unit,
    onOpenArtist: (String?, String) -> Unit,
    onOpenAlbum: (String?, String, String?, String?) -> Unit,
    onShare: () -> Unit,
    viewModel: com.music.bitchord.ui.MainViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    landingPage: ReplayStoryPage = ReplayStoryPage.INTRO,
) {
    val summary = state.summary
    val dark = isDarkMode()
    val textColor = if (dark) Color.White else Color.Black

    // Library cards open this page onto a chart; land once per opening.
    var landed by rememberSaveable(landingPage, state.period) { mutableStateOf(false) }
    LaunchedEffect(landingPage, state.period, summary == null) {
        if (landed) return@LaunchedEffect
        val target = when {
            summary == null || summary.isEmpty -> 0
            else -> replayChartIndex(landingPage)
        }
        listState.scrollToItem(target)
        if (summary != null) landed = true
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ReplayGlow(listState, summary?.artists?.firstOrNull()?.let { ArtistFacts.imageFor(it.title) ?: it.artworkUrl }, dark)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("heading") { Heading(state, { ReplayKindMenuState.open(state.period, onPeriodChange) }, onPeriodChange, onShare, dark) }

            when {
                state.loading && summary == null -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(64.dp), Alignment.Center) {
                        CircularProgressIndicator(color = textColor.copy(alpha = 0.6f))
                    }
                }
                summary == null || summary.isEmpty -> item("empty") { EmptyReplay(state.period, dark) }
                else -> {
                    item("sentence") { MinutesSentence(summary, onOpenStory, dark) }
                    if (summary.artists.isNotEmpty()) {
                        item("artists") {
                            ArtistCards(summary, dark) { artist -> onOpenArtist(artist.browseId, artist.title) }
                        }
                    }
                    item("songs") { TopSongs(summary, dark, onPlaySong) }
                    if (summary.albums.isNotEmpty()) {
                        item("albums") {
                            TopAlbums(summary, dark) { album ->
                                onOpenAlbum(album.browseId, album.title, album.subtitle, album.artworkUrl)
                            }
                        }
                    }
                    item("milestones") { Milestones(summary, dark) }
                    item("closing") { ClosingCard(summary, dark) { title, songs -> openReplayPlaylist(viewModel, title, songs) } }
                    item("habits") { Habits(summary, dark) }
                }
            }
        }
    }
}

/**
 * The kind-of-period choice is asked for from the page but drawn by
 * [ReplayKindMenuHost], at the top of the app. A glass surface samples the page
 * behind it, and drawn inside the page it would be sampling itself.
 */
object ReplayKindMenuState {
    class Request(val selected: ReplayPeriod, val onSelect: (ReplayPeriod, Int) -> Unit)

    var request by mutableStateOf<Request?>(null)
        private set

    fun open(selected: ReplayPeriod, onSelect: (ReplayPeriod, Int) -> Unit) {
        request = Request(selected, onSelect)
    }

    fun close() {
        request = null
    }
}

/** The kind-of-period popup, as the app's own liquid glass. Composed outside the page. */
@Composable
fun ReplayKindMenuHost() {
    val request = ReplayKindMenuState.request ?: return
    val context = LocalContext.current
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsState()
    val shape = RoundedCornerShape(33.dp)
    val motion = rememberPopupMotion()
    val top = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding() + 112.dp
    PopupBox(
        motion = motion,
        onDismiss = ReplayKindMenuState::close,
        scrim = MaterialTheme.colorScheme.scrim.copy(alpha = .35f),
        alignment = Alignment.TopEnd,
    ) {
        Column(
            Modifier
                .padding(top = top, end = 20.dp)
                .width(210.dp)
                .popupCard(motion, from = 0.9f, origin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f))
                .clip(shape)
                .then(
                    if (reduceBlur) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier.alertGlass(shape),
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {},
                )
                .padding(vertical = 8.dp),
        ) {
            ReplayPeriod.entries.forEach { period ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = animatedExit { request.onSelect(period, 0); ReplayKindMenuState.close() })
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp), Alignment.Center) {
                        if (period == request.selected) {
                            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        period.kindName(context),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/**
 * The glow behind the whole page: five radial gradients on slow, overlapping
 * orbits, red, orange and yellow pulled towards the colours of the top artist.
 * Drawn in the draw phase only — no blur, no layers. Stands still under
 * reduced motion.
 */
@Composable
private fun ReplayGlow(listState: LazyListState, artistArtwork: String?, dark: Boolean) {
    val context = LocalContext.current
    val appReduce by AppSettings.reduceAnimation.collectAsState()
    val systemOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val still = appReduce || systemOff
    val phase = if (still) {
        remember { androidx.compose.runtime.mutableStateOf(0.6f) }
    } else {
        rememberInfiniteTransition(label = "replayGlow").animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
    }
    // The warm base, tinted by the artist's own palette once it is known.
    val palette = rememberArtworkColors(artistArtwork).colors
    val tint = artistArtwork != null
    fun mix(base: Color, i: Int): Color =
        if (tint && palette.isNotEmpty()) lerp(base, palette[i % palette.size].copy(alpha = 1f), 0.62f) else base
    val spec = spring<Color>(dampingRatio = 1f, stiffness = 300f)
    val c0 by animateColorAsState(mix(ReplayRed, 0), spec, label = "c0")
    val c1 by animateColorAsState(mix(ReplayOrange, 1), spec, label = "c1")
    val c2 by animateColorAsState(mix(ReplayYellow, 2), spec, label = "c2")
    val c3 by animateColorAsState(mix(ReplayRed, 3), spec, label = "c3")
    val c4 by animateColorAsState(mix(ReplayOrange, 0), spec, label = "c4")
    val background = MaterialTheme.colorScheme.background
    val strength = if (dark) 1f else 0.7f
    fun androidx.compose.ui.graphics.drawscope.DrawScope.glow(color: Color, cx: Float, cy: Float, radius: Float, alpha: Float) {
        drawRect(
            Brush.radialGradient(
                0f to color.copy(alpha = alpha * strength),
                0.5f to color.copy(alpha = alpha * 0.5f * strength),
                1f to color.copy(alpha = 0f),
                center = Offset(cx, cy),
                radius = radius,
            ),
        )
    }
    Box(Modifier.fillMaxSize()) {
        // The start of the page: a warm wash that scrolls away with the header.
        Box(
            Modifier
                .fillMaxWidth()
                .height(620.dp)
                .graphicsLayer {
                    translationY = if (listState.firstVisibleItemIndex == 0) {
                        -listState.firstVisibleItemScrollOffset.toFloat()
                    } else {
                        -size.height
                    }
                }
                .drawBehind {
                    val t = phase.value
                    val w = size.width
                    val h = size.height
                    glow(c0, w * (0.15f + 0.32f * sin(t)), h * (0.18f + 0.12f * cos(t * 2f)), w * 1.1f, 0.95f)
                    glow(c1, w * (0.70f + 0.28f * cos(t + 1.3f)), h * (0.08f + 0.12f * sin(t + 0.7f)), w * 1.0f, 0.95f)
                    glow(c2, w * (0.95f + 0.25f * sin(t * 2f + 2.4f)), h * (0.30f + 0.14f * cos(t + 2.0f)), w * 0.9f, 0.9f)
                    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to background))
                },
        )
        // The end of the page: the same glow, rising from the bottom as the last
        // card comes into view.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(560.dp)
                .graphicsLayer {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    val shown = if (last != null && last.index == info.totalItemsCount - 1) {
                        ((info.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    translationY = (1f - shown) * size.height
                }
                .drawBehind {
                    val t = phase.value
                    val w = size.width
                    val h = size.height
                    glow(c3, w * (0.10f + 0.35f * cos(t + 4.0f)), h * (0.80f + 0.12f * sin(t + 3.1f)), w * 1.2f, 0.85f)
                    glow(c4, w * (0.90f + 0.30f * sin(t + 5.2f)), h * (0.70f + 0.14f * cos(t * 2f + 1.0f)), w * 1.1f, 0.85f)
                    glow(c1, w * (0.50f + 0.30f * sin(t + 2.0f)), h * (1.0f + 0.08f * cos(t + 1.0f)), w * 1.0f, 0.8f)
                    drawRect(Brush.verticalGradient(0f to background, 0.45f to Color.Transparent))
                },
        )
    }
}

// ── Heading ─────────────────────────────────────────────────────────────────

@Composable
private fun Heading(
    state: ReplayState,
    onOpenMenu: () -> Unit,
    onPeriodChange: (ReplayPeriod, Int) -> Unit,
    onShare: () -> Unit,
    dark: Boolean,
) {
    val textColor = if (dark) Color.White else Color.Black
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.replay),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.W800,
                color = textColor,
                modifier = Modifier.weight(1f),
            )
            KindPill(state.period, onOpenMenu, dark)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(textColor.copy(alpha = 0.16f))
                    .clickable(onClick = onShare),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.IosShare, stringResource(R.string.share_my_replay), tint = textColor, modifier = Modifier.size(20.dp))
            }
        }
        if (state.period != ReplayPeriod.ALL_TIME) {
            Spacer(Modifier.height(22.dp))
            PeriodRow(state, onPeriodChange, dark)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** The pill at the right of the title: which kind of stretch. Opens [KindMenu]. */
@Composable
private fun KindPill(selected: ReplayPeriod, onClick: () -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .clip(CircleShape)
            .background(textColor.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            selected.kindName(context),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W600,
            color = textColor,
            maxLines = 1,
        )
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = textColor, modifier = Modifier.size(22.dp))
    }
}

/** The row of stretches: the chosen one in full white, the rest dimmed. */
@Composable
private fun PeriodRow(state: ReplayState, onPeriodChange: (ReplayPeriod, Int) -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    val back = maxBack(state.period, state.earliest)
    val offsets = remember(state.period, back) { (-back..0).toList() }
    val rowState = rememberLazyListState()
    // Newest on the right, and the chosen one scrolled into view.
    LaunchedEffect(state.period, back) {
        rowState.scrollToItem((offsets.indexOf(state.offset)).coerceAtLeast(0))
        rowState.scrollToItem(offsets.lastIndex.coerceAtLeast(0), scrollOffset = 0)
    }
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(offsets, key = { it }) { offset ->
            val active = offset == state.offset
            Text(
                text = periodShort(context, state.period, offset),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.W600,
                color = if (active) textColor else textColor.copy(alpha = 0.38f),
                maxLines = 1,
                modifier = Modifier.clickable { onPeriodChange(state.period, offset) },
            )
        }
    }
}

@Composable
private fun MinutesSentence(summary: ReplaySummary, onOpenStory: (ReplayStoryPage) -> Unit, dark: Boolean) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    val dim = textColor.copy(alpha = 0.45f)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = dim)) { append(stringResource(R.string.replay_you_listened) + " ") }
        withStyle(SpanStyle(color = textColor)) {
            append(context.getString(R.string.replay_minutes_word, formatMinutes(summary.totalMs)))
        }
        withStyle(SpanStyle(color = dim)) { append(" " + summary.minutesWhen(context)) }
    }
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        Text(
            text = text,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.W700,
        )
        Spacer(Modifier.height(18.dp))
        ReplayActionRow(BitChordIcons.Play, stringResource(R.string.play_your_replay), dark) {
            onOpenStory(ReplayStoryPage.INTRO)
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ── Sections ────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String, dark: Boolean, onClick: (() -> Unit)? = null) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.W700, color = textColor)
        Spacer(Modifier.width(6.dp))
        Icon(BitChordIcons.ChevronRight, null, tint = textColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ArtistCards(summary: ReplaySummary, dark: Boolean, onClick: (com.music.bitchord.data.stats.RankedEntry) -> Unit) {
    val rowState = rememberLazyListState()
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionHeader(stringResource(R.string.replay_your_top_artists), dark)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            flingBehavior = rememberSnapFlingBehavior(rowState),
        ) {
            itemsIndexed(summary.artists.take(10), key = { _, a -> a.title }) { index, artist ->
                ArtistCard(index + 1, artist, dark) { onClick(artist) }
            }
        }
    }
}

@Composable
private fun ArtistCard(rank: Int, artist: com.music.bitchord.data.stats.RankedEntry, dark: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(22.dp)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = (screenWidth - (PAGE_GUTTER + 4.dp) * 2 - 36.dp).coerceIn(210.dp, 300.dp)
    Box(
        Modifier
            .width(cardWidth)
            .aspectRatio(0.76f)
            .clip(shape)
            .background(Color(0xFF2A2A2C))
            .clickable(onClick = onClick),
    ) {
        // The artist's own picture, never a sleeve: until one is known the card shows initials.
        val photo = ArtistFacts.imageFor(artist.title)
        if (photo != null) {
            // Full colour for the first, draining towards the right.
            val saturation = (1f - (rank - 1) * 0.13f).coerceAtLeast(0f)
            AsyncImage(
                model = photo.artworkAt(600),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(saturation) }),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            InitialTile(artist.title, cardWidth, RoundedCornerShape(0.dp))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)),
            ),
        )
        Text(
            text = rank.toString(),
            color = Color.White,
            fontSize = 64.sp,
            fontWeight = FontWeight.W700,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                artist.title, color = Color.White, fontWeight = FontWeight.W600,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                context.getString(R.string.replay_minutes_word, formatMinutes(artist.ms)),
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TopSongs(summary: ReplaySummary, dark: Boolean, onPlaySong: (Song) -> Unit) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    val variant = textColor.copy(alpha = 0.55f)
    val pages = summary.songs.take(20).chunked(4)
    val rowState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val pageWidth = screenWidth - (PAGE_GUTTER + 4.dp) * 2 - 28.dp
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionHeader(stringResource(R.string.replay_your_top_songs), dark)
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            flingBehavior = rememberSnapFlingBehavior(rowState),
        ) {
            itemsIndexed(pages) { pageIndex, page ->
                Column(Modifier.width(pageWidth)) {
                    page.forEachIndexed { i, entry ->
                        val rank = pageIndex * 4 + i + 1
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPlaySong(entry.song) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (entry.song.thumbnailUrl != null) {
                                AsyncImage(
                                    model = entry.song.thumbnailUrl.artworkAt(ROW_ART_PX),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(7.dp)),
                                )
                            } else {
                                InitialTile(entry.song.title, 50.dp, RoundedCornerShape(7.dp))
                            }
                            Text(
                                rank.toString(),
                                color = textColor,
                                fontWeight = FontWeight.W600,
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(44.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.song.title, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W600,
                                )
                                Text(
                                    listOfNotNull(
                                        entry.song.artist.takeIf { it.isNotBlank() },
                                        context.getString(R.string.replay_plays_n, grouped(entry.plays.toLong())),
                                    ).joinToString(" · "),
                                    color = variant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopAlbums(summary: ReplaySummary, dark: Boolean, onClick: (com.music.bitchord.data.stats.RankedEntry) -> Unit) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionHeader(stringResource(R.string.replay_your_top_albums), dark)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(summary.albums.take(10), key = { i, a -> "$i${a.title}${a.subtitle}" }) { index, album ->
                Column(Modifier.width(150.dp).clickable { onClick(album) }) {
                    if (album.artworkUrl != null) {
                        AsyncImage(
                            model = album.artworkUrl.artworkAt(400),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        InitialTile(album.title, 150.dp, RoundedCornerShape(10.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${index + 1}", color = textColor, fontWeight = FontWeight.W600)
                    Text(album.title, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.W600)
                    album.subtitle?.let {
                        Text(it, color = textColor.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        context.getString(R.string.replay_minutes_word, formatMinutes(album.ms)),
                        color = textColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private data class Milestone(val title: String, val value: Long, val next: Long, val colors: List<Color>)

private fun nextTier(value: Long, tiers: List<Long>): Long = tiers.firstOrNull { it > value } ?: tiers.last()

/** Rings that fill towards the next round number of minutes, songs and artists. */
@Composable
private fun Milestones(summary: ReplaySummary, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    val items = listOf(
        Milestone(
            stringResource(R.string.replay_milestone_minutes), summary.minutes,
            nextTier(summary.minutes, listOf(60, 100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000)),
            listOf(Color(0xFF30D158), Color(0xFF0A84FF), Color(0xFFBF5AF2)),
        ),
        Milestone(
            stringResource(R.string.replay_milestone_songs), summary.totalPlays.toLong(),
            nextTier(summary.totalPlays.toLong(), listOf(10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000)),
            listOf(Color(0xFFFFD60A), Color(0xFFFF9F0A), Color(0xFFFF453A)),
        ),
        Milestone(
            stringResource(R.string.replay_milestone_artists), summary.distinctArtists.toLong(),
            nextTier(summary.distinctArtists.toLong(), listOf(5, 10, 25, 50, 100, 250, 500, 1_000)),
            listOf(Color(0xFF64D2FF), Color(0xFF5E5CE6), Color(0xFFFF375F)),
        ),
    )
    Column(Modifier.padding(bottom = 18.dp)) {
        SectionHeader(stringResource(R.string.replay_your_milestones), dark)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.title }) { m ->
                Column(
                    Modifier
                        .width(170.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(textColor.copy(alpha = 0.09f))
                        .padding(vertical = 18.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(112.dp), Alignment.Center) {
                        val progress = (m.value.toFloat() / m.next).coerceIn(0f, 1f)
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 9.dp.toPx()
                            m.colors.forEachIndexed { i, color ->
                                val inset = stroke / 2 + i * (stroke + 3.dp.toPx())
                                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                                drawArc(
                                    color.copy(alpha = 0.22f), -90f, 360f, false,
                                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke),
                                )
                                drawArc(
                                    color, -90f, 360f * progress, false,
                                    topLeft = Offset(inset, inset), size = arcSize,
                                    style = Stroke(stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                        Text(
                            grouped(m.value), color = textColor, fontWeight = FontWeight.W800,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(m.title, color = textColor, fontWeight = FontWeight.W600, textAlign = TextAlign.Center, maxLines = 1)
                    Text(
                        stringResource(R.string.replay_milestone_next, grouped(m.next)),
                        color = textColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The end of the page: the whole Replay as a playlist — its songs,
 * kept as a playlist of its own, which a tap opens.
 */
@Composable
private fun ClosingCard(
    summary: ReplaySummary,
    dark: Boolean,
    onOpenPlaylist: (String, List<Song>) -> Unit,
) {
    val context = LocalContext.current
    val textColor = if (dark) Color.White else Color.Black
    val songs = remember(summary) { summary.songs.take(50).map { it.song } }
    val year = (summary.rangeEnd ?: LocalDate.now()).year.toString()
    val label = summary.localizedLabel(context)
    val allTime = summary.period == ReplayPeriod.ALL_TIME
    val name = context.getString(R.string.replay_playlist_title, if (allTime) label else if (summary.period == ReplayPeriod.THIS_YEAR) year else label)
    val total = formatListening(context, summary.songs.take(50).sumOf { it.ms })
    // The card's gradient takes the colours of the top artist's photo.
    val photo = summary.artists.firstOrNull()?.let { ArtistFacts.imageFor(it.title) ?: it.artworkUrl }
    val palette = rememberArtworkColors(photo).colors
    fun tinted(base: Color, i: Int): Color =
        if (photo != null && palette.isNotEmpty()) lerp(base, palette[i % palette.size].copy(alpha = 1f), 0.65f) else base
    val spec = spring<Color>(dampingRatio = 1f, stiffness = 300f)
    val g0 by animateColorAsState(tinted(Color(0xFFFFC107), 0), spec, label = "g0")
    val g1 by animateColorAsState(tinted(Color(0xFFFFA000), 1), spec, label = "g1")
    val g2 by animateColorAsState(tinted(Color(0xFFFF3D2E), 2), spec, label = "g2")
    val g3 by animateColorAsState(tinted(Color(0xFFB04A12), 3), spec, label = "g3")
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp).padding(bottom = 24.dp)) {
        Text(
            name, color = textColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.W700,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.95f)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.verticalGradient(
                        0f to g0, 0.4f to g1, 0.62f to g2, 1f to g3,
                    ),
                )
                .clickable { onOpenPlaylist(name, songs) },
        ) {
            Text(
                stringResource(R.string.replay), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.W800,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 28.dp),
            )
            Text(
                if (allTime) "∞" else year,
                color = Color.White, fontSize = 112.sp, fontWeight = FontWeight.W800, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 22.dp),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!allTime && summary.period != ReplayPeriod.THIS_YEAR) {
                    Text(label, color = Color.White, fontWeight = FontWeight.W700, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    summary.artists.take(5).joinToString(", ") { it.title } + if (summary.artists.size > 5) "…" else "",
                    color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    context.getString(R.string.replay_playlist_songs, songs.size.toString(), total),
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Lazy-list index of the section [page] belongs to: heading, sentence, artists, songs, albums. */
private fun replayChartIndex(page: ReplayStoryPage): Int = when (page) {
    ReplayStoryPage.ARTISTS -> 2
    ReplayStoryPage.SONGS -> 3
    ReplayStoryPage.ALBUMS -> 4
    else -> 0
}

// ── The cards ───────────────────────────────────────────────────────────────

/**
 * The wallet of cards, wherever it is drawn.
 *
 * It lives on the Library page rather than here — see [ReplayScreen]'s note on
 * where each half of Replay belongs — but it is defined alongside the page it
 * summarises, because the two have to keep saying the same thing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReplayCardRow(
    cards: List<ReplayHeroCard>,
    holder: String,
    memberSince: String?,
    onCardClick: (ReplayStoryPage) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = PAGE_GUTTER + 4.dp),
) {
    val rowState = rememberLazyListState()
    LazyRow(
        modifier = modifier,
        state = rowState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(lazyListState = rowState),
    ) {
        items(cards, key = { it.label }) { card ->
            ReplayCreditCard(
                label = card.label,
                value = card.value,
                detail = card.detail,
                artworkUrl = card.artworkUrl,
                holder = holder,
                memberSince = memberSince,
                onClick = { onCardClick(card.page) },
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

/**
 * The two things this page can do, drawn the same way.
 *
 * They sit at either end of it and are the same kind of act — open the Replay,
 * send the Replay — so they have no business looking unalike. The share button
 * used to be a filled red slab, which on a page with no destructive control on
 * it was the loudest thing there and read like one.
 */
@Composable
private fun ReplayActionRow(icon: ImageVector, label: String, dark: Boolean, onClick: () -> Unit) {
    val textColor = if (dark) Color.White else Color.Black
    Row(
        Modifier
            .padding(horizontal = PAGE_GUTTER + 4.dp, vertical = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(textColor.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = textColor.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Tail ────────────────────────────────────────────────────────────────────

@Composable
private fun Habits(summary: ReplaySummary, dark: Boolean) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 4.dp)) {
        SectionTitleInline(stringResource(R.string.listening_shape), dark)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(stringResource(R.string.songs), summary.distinctSongs.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.artists), summary.distinctArtists.toString(), dark, Modifier.weight(1f))
            StatTile(stringResource(R.string.albums), summary.distinctAlbums.toString(), dark, Modifier.weight(1f))
        }
        summary.peakHour?.let {
            Spacer(Modifier.height(10.dp))
            Note(stringResource(R.string.listen_most_around, formatHour(context, it)), dark)
        }
        summary.busiestDay?.let {
            Spacer(Modifier.height(6.dp))
            Note(
                stringResource(
                    R.string.biggest_day_note,
                    formatDay(context, it),
                    formatListening(context, summary.busiestDayMs),
                ),
                dark,
            )
        }
    }
}

@Composable
private fun SectionTitleInline(text: String, dark: Boolean) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.W800,
        color = textColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Column(
        modifier
            .clip(RoundedCornerShape(17.dp))
            .background(textColor.copy(alpha = 0.09f))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = textColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.55f),
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun Note(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    val textColor = if (dark) Color.White else Color.Black
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor.copy(alpha = 0.6f),
        modifier = modifier,
    )
}

@Composable
private fun EmptyReplay(period: ReplayPeriod, dark: Boolean) {
    val emptyColor = if (dark) Color.White else Color.Black
    val emptyVariant = if (dark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = BitChordIcons.Clock,
            contentDescription = null,
            tint = emptyVariant,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.not_enough_listening),
            style = MaterialTheme.typography.titleLarge,
            color = emptyColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (period) {
                ReplayPeriod.THIS_WEEK -> stringResource(R.string.not_enough_this_week)
                ReplayPeriod.THIS_MONTH -> stringResource(R.string.not_enough_this_month)
                else -> stringResource(R.string.replay_empty_description)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = emptyColor.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How far down each chart the page goes.
 *
 * Five. Ten was the first guess and it made the page a scroll of forty rows
 * whose second half nobody has an opinion about — a top five is a result, and a
 * top ten is a list.
 */
private const val CHART_LENGTH = 5
