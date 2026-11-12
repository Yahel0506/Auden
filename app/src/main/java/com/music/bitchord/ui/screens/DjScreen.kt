package com.music.bitchord.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.ui.player.DjMeshPalettes
import com.music.bitchord.ui.player.DjRing
import com.music.bitchord.ui.player.MESH_CYCLE_MILLIS
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.cyclingMeshPalette
import dev.auden.dj.DjCardKind
import dev.auden.dj.DjHome
import dev.auden.dj.DjMood
import dev.auden.dj.DjPool
import dev.auden.dj.DjSavedSets
import dev.auden.dj.DjSection
import dev.auden.dj.DjSession
import dev.auden.dj.DjSetCard
import dev.auden.dj.DjShelves
import dev.auden.dj.DjTrack
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.Random

// One spacing system for the whole page: a gutter, a gap before each section, a gap under its title, a gap
// between neighbours. Nothing below picks a spacing of its own.
private val Gutter = 20.dp
private val SectionGap = 32.dp
private val TitleGap = 14.dp
private val ItemGap = 12.dp
private val Inset = 14.dp

private val Ink = Color(0xFF14121C)
private val Glass = Color.White.copy(alpha = 0.14f)
private val GlassRim = Color.White.copy(alpha = 0.22f)

/**
 * Auden DJ's page: the DJ's own place in the app. Starting a set, and the sets the DJ prepares from what it
 * knows of the listener — each with the reason it is offered, a way to start it, to draw it again, and to keep it.
 *
 * What goes where, and which sets earn a place at all, is [DjShelves]'s. Every section is its own shape on purpose,
 * so the page reads as a place with parts and not one kind of card repeated: a hero and chips to start; tall covers
 * for what is made for you; rows for what was left behind; wide cards for discoveries; a featured tile and two
 * smaller ones for the moment; round artists, square albums and chips to explore; slim rows for the sessions. It
 * stands on the DJ's own moving mesh across the whole page.
 */
@Composable
fun DjScreen(
    contentPadding: PaddingValues,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val djState by DjSession.state.collectAsStateWithLifecycle()
    val sessions by DjSavedSets.entries.collectAsStateWithLifecycle()
    val variants = remember { mutableStateMapOf<String, Int>() }
    var tracks by remember { mutableStateOf<List<DjTrack>?>(null) }
    LaunchedEffect(Unit) {
        DjSavedSets.load(context)
        tracks = DjPool.load(context).tracks
    }

    val clock = remember { LocalDateTime.now() }
    val home: DjHome? = remember(tracks, variants.toMap()) {
        tracks?.let {
            DjShelves.build(
                it, System.currentTimeMillis(), clock.hour, clock.dayOfWeek.value >= 6,
                variants = variants.toMap(), genresOf = ArtistFacts::genresForDj,
            )
        }
    }
    // Each set's words, worked out here where the strings are, so the actions below can name what they start.
    val words: Map<String, Pair<String, String?>> = home?.all?.associate { it.id to cardWords(it) }.orEmpty()

    fun entryFor(card: DjSetCard): DjSavedSets.Entry {
        val r = card.request
        val (title, reason) = words[card.id] ?: ("" to null)
        return DjSavedSets.Entry(
            cardId = card.id, kind = card.kind, subject = card.subject, number = card.number, mood = card.mood,
            title = title, reason = reason, artist = r?.artist, decade = r?.decade, genre = r?.genre,
            favorites = r?.favorites == true, fresh = r?.fresh == true, rediscover = r?.rediscover == true,
            album = r?.album, playlist = r?.playlist, discover = r?.discover == true, discoverSeed = r?.discoverSeed,
            discoverFar = r?.discoverFar == true, text = r?.text.orEmpty(), covers = card.covers.take(3),
        )
    }

    fun isSaved(card: DjSetCard): Boolean {
        val e = entryFor(card)
        return sessions.any { it.saved && it.cardId == e.cardId && it.artist == e.artist && it.album == e.album && it.text == e.text }
    }

    fun startCard(card: DjSetCard) {
        // A surprise is a set of the page's, drawn at random, leaving out the ones started lately.
        val chosen = if (card.kind == DjCardKind.SURPRISE) {
            home?.let { DjShelves.surprise(it, sessions.take(6).map { s -> s.cardId }.toSet(), Random()) } ?: card
        } else {
            card
        }
        val entry = entryFor(chosen)
        DjSavedSets.logStart(context, entry)
        DjSession.start(context, entry.title, chosen.request)
        onOpenPlayer()
    }

    fun startEntry(entry: DjSavedSets.Entry) {
        DjSavedSets.logStart(context, entry)
        if (entry.typed) DjSession.start(context, entry.text) else DjSession.start(context, entry.title, entry.request())
        onOpenPlayer()
    }

    fun startTyped(text: String) {
        val ask = text.trim().ifEmpty { return }
        DjSavedSets.logStart(
            context,
            DjSavedSets.Entry(cardId = "typed:${ask.lowercase()}", kind = DjCardKind.FOR_YOU, title = ask, text = ask, typed = true),
        )
        DjSession.start(context, ask)
        onOpenPlayer()
    }

    fun again(card: DjSetCard) {
        val key = variantKey(card)
        variants[key] = (variants[key] ?: 0) + 1
    }

    val actions = SetActions(
        words = words,
        isSaved = ::isSaved,
        onStart = ::startCard,
        onAgain = ::again,
        onSave = { card, saved -> DjSavedSets.setSaved(context, entryFor(card), saved) },
    )

    Box(modifier.fillMaxSize()) {
        // The DJ's own backdrop across the whole page, never at rest: the mesh drifts, and its colours move on.
        MeshGradientBackground(
            palette = cyclingMeshPalette(DjMeshPalettes),
            modifier = Modifier.fillMaxSize(),
            continuous = true,
            driftMillis = 6_000,
            blurRadius = 56.dp,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Just clear of the floating buttons at the top (the page's own top inset already holds the bar's space),
            // and of the bars at the foot.
            contentPadding = PaddingValues(top = (contentPadding.calculateTopPadding() - 20.dp).coerceAtLeast(0.dp), bottom = 180.dp),
        ) {
            item(key = "header") { Header() }
            if (djState.active) item(key = "on-air") {
                Box(Modifier.padding(top = ItemGap)) { OnAir(theme = djState.theme, onOpen = onOpenPlayer, onStop = { DjSession.stop() }) }
            }
            when {
                home == null -> item(key = "loading") { Quiet(stringResource(R.string.dj_home_loading)) }
                home.sections.isEmpty() -> item(key = "empty") { Quiet(stringResource(R.string.dj_home_empty)) }
                else -> home.sections.forEachIndexed { order, (section, cards) ->
                    item(key = "sec-${section.name}") {
                        Section(title = sectionName(section), order = order) {
                            when (section) {
                                DjSection.START -> StartBlock(cards, actions, onTyped = ::startTyped)
                                DjSection.FOR_YOU -> CoverRow(cards, actions)
                                DjSection.COME_BACK -> RowList(cards, actions)
                                DjSection.DISCOVER -> WideRow(cards, actions)
                                DjSection.MOMENT -> MomentBlock(cards, actions)
                                DjSection.EXPLORE -> ExploreBlock(cards, actions, onShuffle = { cards.forEach(::again) })
                            }
                        }
                    }
                }
            }
            // Sessions: what was kept and what was started lately. Always last; absent until there is one.
            if (sessions.isNotEmpty()) item(key = "sec-sessions") {
                Section(title = stringResource(R.string.dj_sec_sessions), order = 7) {
                    SessionList(
                        saved = sessions.filter { it.saved }, recent = sessions.filter { !it.saved },
                        onStart = ::startEntry, onSave = { e, s -> DjSavedSets.setSaved(context, e, s) },
                    )
                }
            }
        }
    }
}

/** What a set can do, handed down to every shape it is drawn in. */
private class SetActions(
    val words: Map<String, Pair<String, String?>>,
    val isSaved: (DjSetCard) -> Boolean,
    val onStart: (DjSetCard) -> Unit,
    val onAgain: (DjSetCard) -> Unit,
    val onSave: (DjSetCard, Boolean) -> Unit,
) {
    fun title(card: DjSetCard) = words[card.id]?.first.orEmpty()
    fun reason(card: DjSetCard) = words[card.id]?.second
}

/** The part of the page a set is drawn again in: sets drawn from one list share a key. */
private fun variantKey(card: DjSetCard): String = when (card.kind) {
    DjCardKind.TOP_ARTIST -> "top-artist"
    DjCardKind.LOST_ARTIST -> "lost-artist"
    DjCardKind.LOST_ALBUM -> "lost-album"
    DjCardKind.DISCOVER_ARTIST -> "discover-artist"
    DjCardKind.THEME_ARTIST -> "theme-artist"
    DjCardKind.THEME_ALBUM -> "theme-album"
    DjCardKind.THEME_DECADE -> "theme-decade"
    DjCardKind.THEME_GENRE -> "theme-genre"
    else -> card.id
}

// ── Page furniture ──────────────────────────────────────────────────────────

@Composable
private fun Header() {
    Column(Modifier.padding(horizontal = Gutter)) {
        Text(stringResource(R.string.dj_home_title), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.dj_home_subtitle), color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun Quiet(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = Gutter, vertical = SectionGap))
}

/** A section: the same gap before it and under its title everywhere, its title arriving with a spring. */
@Composable
private fun Section(title: String, order: Int, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(order * 70L)
        enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    Column(Modifier.padding(top = SectionGap)) {
        Row(
            modifier = Modifier
                .padding(horizontal = Gutter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = enter.value
                    translationX = (1f - enter.value) * -36f
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(TitleGap))
        content()
    }
}

@Composable
private fun OnAir(theme: String?, onOpen: () -> Unit, onStop: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "onAir").animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "onAirPulse",
    )
    Row(
        modifier = Modifier
            .padding(horizontal = Gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Glass)
            .border(1.dp, GlassRim, RoundedCornerShape(22.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = Inset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(pulse.value).background(Color(0xFFFF8FA9), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dj_on_air), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (!theme.isNullOrBlank()) Text(theme, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(R.string.dj_stop), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onStop).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

// ── Artwork ─────────────────────────────────────────────────────────────────

@Composable
private fun Cover(url: String?, modifier: Modifier = Modifier, px: Int = CARD_ART_PX) {
    AsyncImage(
        model = url.artworkAt(px),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color.White.copy(alpha = 0.12f)),
    )
}

/** Up to four covers as one picture: one whole, two side by side, one large and two small, or a grid. */
@Composable
private fun Mosaic(covers: List<String>, modifier: Modifier = Modifier, px: Int = CARD_ART_PX) {
    when (covers.size) {
        0 -> Box(modifier.background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.08f)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.3f))
        }
        1 -> Cover(covers[0], modifier, px)
        2 -> Row(modifier) {
            covers.forEach { Cover(it, Modifier.weight(1f).fillMaxHeight(), px) }
        }
        3 -> Row(modifier) {
            Cover(covers[0], Modifier.weight(1f).fillMaxHeight(), px)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Cover(covers[1], Modifier.weight(1f).fillMaxWidth(), px)
                Cover(covers[2], Modifier.weight(1f).fillMaxWidth(), px)
            }
        }
        else -> Column(modifier) {
            Row(Modifier.weight(1f)) {
                Cover(covers[0], Modifier.weight(1f).fillMaxHeight(), px)
                Cover(covers[1], Modifier.weight(1f).fillMaxHeight(), px)
            }
            Row(Modifier.weight(1f)) {
                Cover(covers[2], Modifier.weight(1f).fillMaxHeight(), px)
                Cover(covers[3], Modifier.weight(1f).fillMaxHeight(), px)
            }
        }
    }
}

/** A spring in as its row is drawn, staggered by place; and a press that gives under the finger. */
@Composable
private fun rememberEntrance(index: Int): Float {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        enter.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
    }
    return enter.value
}

@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return s
}

/** The two small actions a set has besides starting: another version, and keeping it. */
@Composable
private fun SmallActions(card: DjSetCard, actions: SetActions, onDark: Boolean = true) {
    val saved = actions.isSaved(card)
    val turn = remember { Animatable(0f) }
    var turns by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(turns) { if (turns != 0f) turn.animateTo(turns, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)) }
    val pop by animateFloatAsState(if (saved) 1f else 0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium), label = "pop")
    val fill = if (onDark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.16f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.regenerable) RoundIcon(Icons.Rounded.Refresh, stringResource(R.string.dj_card_again), fill, Modifier.graphicsLayer { rotationZ = turn.value }) {
            actions.onAgain(card)
            turns += 360f
        }
        RoundIcon(
            if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            stringResource(if (saved) R.string.dj_card_unsave else R.string.dj_card_save), fill,
            Modifier.graphicsLayer { scaleX = 1f + 0.25f * pop; scaleY = 1f + 0.25f * pop },
        ) { actions.onSave(card, !saved) }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, fill: Color, modifier: Modifier = Modifier, size: Dp = 32.dp, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}

// ── Start ───────────────────────────────────────────────────────────────────

/** The hero, one row of chips (a surprise, then the moods) and the line to type to: three things, not nine. */
@Composable
private fun StartBlock(cards: List<DjSetCard>, actions: SetActions, onTyped: (String) -> Unit) {
    val forYou = cards.first { it.kind == DjCardKind.FOR_YOU }
    val surprise = cards.first { it.kind == DjCardKind.SURPRISE }
    val moods = cards.filter { it.kind == DjCardKind.MOOD }
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        Hero(forYou, actions)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.layoutNoGutter()) {
            item(key = "surprise") { SurpriseChip(actions.title(surprise)) { actions.onStart(surprise) } }
            itemsIndexed(moods, key = { _, c -> c.id }) { index, card -> MoodChip(card, actions.title(card), index) { actions.onStart(card) } }
        }
        AskField(onSend = onTyped)
    }
}

/** Lets a row sit in the page's gutter without a second set of padding to keep in step. */
private fun Modifier.layoutNoGutter(): Modifier = this

@Composable
private fun Hero(card: DjSetCard, actions: SetActions) {
    val source = remember { MutableInteractionSource() }
    val press = pressScale(source)
    val enter = rememberEntrance(0)
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(208.dp)
            .graphicsLayer {
                scaleX = press * (0.92f + 0.08f * enter); scaleY = press * (0.92f + 0.08f * enter); alpha = enter
            }
            .clip(shape)
            .border(1.dp, GlassRim, shape)
            .clickable(interactionSource = source, indication = null) { actions.onStart(card) },
    ) {
        // The listener's own covers, large and dimmed, behind a wash that keeps the words readable.
        Mosaic(card.covers, Modifier.fillMaxSize().alpha(0.55f))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.1f)))))
        DjRing(level = { 0f }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(150.dp), talking = true)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 140.dp)) {
            Text(actions.title(card), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(actions.reason(card).orEmpty(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            PlayPill(stringResource(R.string.dj_card_play))
        }
    }
}

@Composable
private fun PlayPill(label: String) {
    Row(
        modifier = Modifier.clip(CircleShape).background(Color.White).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun SurpriseChip(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val spin = remember { Animatable(0f) }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) spin.animateTo(spin.value + 360f, tween(700)) }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin.value })
        Spacer(Modifier.width(8.dp))
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun MoodChip(card: DjSetCard, label: String, index: Int, onClick: () -> Unit) {
    val enter = rememberEntrance(index + 1)
    val source = remember { MutableInteractionSource() }
    val press = pressScale(source)
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = press * (0.6f + 0.4f * enter); scaleY = press * (0.6f + 0.4f * enter); alpha = enter }
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassRim, CircleShape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(moodColor(card.mood), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** What the listener types to the DJ: anything it understands — an artist, a decade, a feeling. */
@Composable
private fun AskField(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val send = { text.trim().takeIf { it.isNotEmpty() }?.let { onSend(it); text = "" }; Unit }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Glass)
            .border(1.dp, GlassRim, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(stringResource(R.string.dj_ask_hint), color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(140) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        val ready = text.isNotBlank()
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (ready) Color.White else Color.White.copy(alpha = 0.18f)).clickable(enabled = ready) { send() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.dj_ask_send),
                tint = if (ready) Ink else Color.White.copy(alpha = 0.6f), modifier = Modifier.size(19.dp),
            )
        }
    }
}

// ── Made for you: tall covers ───────────────────────────────────────────────

@Composable
private fun CoverRow(cards: List<DjSetCard>, actions: SetActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
        itemsIndexed(cards, key = { _, c -> c.id }) { index, card ->
            val enter = rememberEntrance(index)
            val source = remember { MutableInteractionSource() }
            val press = pressScale(source)
            val shape = RoundedCornerShape(26.dp)
            // The cover breathes: a slow push in and out, each set on its own beat.
            val drift by rememberInfiniteTransition(label = "drift").animateFloat(
                initialValue = 1f, targetValue = 1.07f,
                animationSpec = infiniteRepeatable(tween(7000 + index * 900, easing = LinearEasing), RepeatMode.Reverse), label = "driftScale",
            )
            Box(
                Modifier
                    .width(176.dp)
                    .height(236.dp)
                    .graphicsLayer { scaleX = press * (0.9f + 0.1f * enter); scaleY = press * (0.9f + 0.1f * enter); alpha = enter; translationY = (1f - enter) * 50f }
                    .clip(shape)
                    .border(1.dp, GlassRim, shape)
                    .clickable(interactionSource = source, indication = null) { actions.onStart(card) },
            ) {
                Mosaic(card.covers, Modifier.fillMaxSize().scale(drift))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { SmallActions(card, actions) }
                Column(Modifier.align(Alignment.BottomStart).padding(Inset)) {
                    Text(actions.title(card), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(actions.reason(card).orEmpty(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Listen again: rows ──────────────────────────────────────────────────────

@Composable
private fun RowList(cards: List<DjSetCard>, actions: SetActions) {
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        cards.forEachIndexed { index, card ->
            val enter = rememberEntrance(index)
            val source = remember { MutableInteractionSource() }
            val press = pressScale(source)
            val shape = RoundedCornerShape(22.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = press; scaleY = press; alpha = enter; translationX = (1f - enter) * 80f }
                    .clip(shape)
                    .background(Glass)
                    .border(1.dp, GlassRim, shape)
                    .clickable(interactionSource = source, indication = null) { actions.onStart(card) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mosaic(card.covers, Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)), ROW_ART_PX)
                Spacer(Modifier.width(Inset))
                Column(Modifier.weight(1f)) {
                    Text(actions.title(card), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(actions.reason(card).orEmpty(), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                SmallActions(card, actions, onDark = false)
            }
        }
    }
}

// ── Discover: wide cards ────────────────────────────────────────────────────

@Composable
private fun WideRow(cards: List<DjSetCard>, actions: SetActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
        itemsIndexed(cards, key = { _, c -> c.id }) { index, card ->
            val enter = rememberEntrance(index)
            val source = remember { MutableInteractionSource() }
            val press = pressScale(source)
            val shape = RoundedCornerShape(28.dp)
            val tint = remember(card.id) { Color.hsv(((card.id.hashCode() and 0xFF) / 255f) * 360f, 0.45f, 1f) }
            Box(
                Modifier
                    .width(304.dp)
                    .height(148.dp)
                    .graphicsLayer { scaleX = press * (0.9f + 0.1f * enter); scaleY = press * (0.9f + 0.1f * enter); alpha = enter; translationX = (1f - enter) * 90f }
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.5f), Color.White.copy(alpha = 0.1f))))
                    .border(1.dp, GlassRim, shape)
                    .clickable(interactionSource = source, indication = null) { actions.onStart(card) },
            ) {
                // Their covers fan out from the right edge, one over another, each a little turned.
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
                    card.covers.take(3).forEachIndexed { i, url ->
                        Cover(
                            url, Modifier
                                .offset(x = (-(i * 34)).dp, y = ((i - 1) * 6).dp)
                                .size(84.dp)
                                .graphicsLayer { rotationZ = (i - 1) * 7f }
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                            ROW_ART_PX,
                        )
                    }
                }
                Column(Modifier.align(Alignment.CenterStart).padding(start = 18.dp, end = 150.dp)) {
                    Text(actions.title(card), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(actions.reason(card).orEmpty(), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 10.dp)) { SmallActions(card, actions) }
            }
        }
    }
}

// ── By the moment: a featured tile and two smaller ones ─────────────────────

@Composable
private fun MomentBlock(cards: List<DjSetCard>, actions: SetActions) {
    val first = cards.firstOrNull() ?: return
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        MomentTile(first, actions, featured = true, order = 0, modifier = Modifier.fillMaxWidth().height(156.dp))
        if (cards.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            cards.drop(1).forEachIndexed { i, card ->
                MomentTile(card, actions, featured = false, order = i + 1, modifier = Modifier.weight(1f).height(112.dp))
            }
        }
    }
}

@Composable
private fun MomentTile(card: DjSetCard, actions: SetActions, featured: Boolean, order: Int, modifier: Modifier) {
    val enter = rememberEntrance(order)
    val source = remember { MutableInteractionSource() }
    val press = pressScale(source)
    val color = moodColor(card.mood)
    val shape = RoundedCornerShape(if (featured) 30.dp else 24.dp)
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.25f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(2600 + order * 500, easing = LinearEasing), RepeatMode.Reverse), label = "glowAlpha",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = press * (0.92f + 0.08f * enter); scaleY = press * (0.92f + 0.08f * enter); alpha = enter }
            .clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.7f), color.copy(alpha = 0.18f))))
            .border(1.dp, GlassRim, shape)
            .clickable(interactionSource = source, indication = null) { actions.onStart(card) },
    ) {
        Mosaic(card.covers.take(1), Modifier.fillMaxSize().alpha(glow * 0.55f))
        Column(Modifier.align(Alignment.BottomStart).padding(Inset)) {
            Text(actions.title(card), color = Color.White, fontSize = if (featured) 28.sp else 19.sp, fontWeight = FontWeight.Bold)
            if (featured) Text(actions.reason(card).orEmpty(), color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
        }
        if (featured) Box(Modifier.align(Alignment.TopEnd).padding(Inset)) { PlayPill(stringResource(R.string.dj_card_play)) }
        else Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { RoundIcon(Icons.Rounded.PlayArrow, stringResource(R.string.dj_card_play), Color.White.copy(alpha = 0.22f)) { actions.onStart(card) } }
    }
}

// ── Explore your music: round artists, square albums, chips ─────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreBlock(cards: List<DjSetCard>, actions: SetActions, onShuffle: () -> Unit) {
    val artists = cards.filter { it.kind == DjCardKind.THEME_ARTIST }
    val albums = cards.filter { it.kind == DjCardKind.THEME_ALBUM }
    val chips = cards.filter { it.kind == DjCardKind.THEME_GENRE || it.kind == DjCardKind.THEME_DECADE }
    Column(verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        if (artists.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            itemsIndexed(artists, key = { _, c -> c.id }) { index, card ->
                val enter = rememberEntrance(index)
                val source = remember { MutableInteractionSource() }
                val press = pressScale(source)
                Column(
                    Modifier
                        .width(92.dp)
                        .graphicsLayer { scaleX = press * (0.7f + 0.3f * enter); scaleY = press * (0.7f + 0.3f * enter); alpha = enter }
                        .combinedClickable(interactionSource = source, indication = null, onClick = { actions.onStart(card) }, onLongClick = { actions.onSave(card, !actions.isSaved(card)) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Cover(card.covers.firstOrNull(), Modifier.size(92.dp).clip(CircleShape).border(2.dp, if (actions.isSaved(card)) Color.White else GlassRim, CircleShape), CARD_ART_PX)
                    Spacer(Modifier.height(6.dp))
                    Text(actions.title(card), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (albums.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(ItemGap)) {
            itemsIndexed(albums, key = { _, c -> c.id }) { index, card ->
                val enter = rememberEntrance(index + artists.size)
                val source = remember { MutableInteractionSource() }
                val press = pressScale(source)
                Column(
                    Modifier
                        .width(124.dp)
                        .graphicsLayer { scaleX = press * (0.8f + 0.2f * enter); scaleY = press * (0.8f + 0.2f * enter); alpha = enter }
                        .combinedClickable(interactionSource = source, indication = null, onClick = { actions.onStart(card) }, onLongClick = { actions.onSave(card, !actions.isSaved(card)) }),
                ) {
                    Cover(card.covers.firstOrNull(), Modifier.size(124.dp).clip(RoundedCornerShape(18.dp)).border(if (actions.isSaved(card)) 2.dp else 1.dp, if (actions.isSaved(card)) Color.White else GlassRim, RoundedCornerShape(18.dp)))
                    Spacer(Modifier.height(6.dp))
                    Text(actions.title(card), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card.preview.firstOrNull().orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (chips.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips, key = { it.id }) { card ->
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (actions.isSaved(card)) Color.White.copy(alpha = 0.3f) else Glass)
                        .border(1.dp, GlassRim, CircleShape)
                        .combinedClickable(onClick = { actions.onStart(card) }, onLongClick = { actions.onSave(card, !actions.isSaved(card)) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(actions.title(card), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${card.songs}", color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                }
            }
            item(key = "again") { RoundIcon(Icons.Rounded.Refresh, stringResource(R.string.dj_card_again), Glass, size = 40.dp) { onShuffle() } }
        }
    }
}

// ── Your sessions: slim rows ────────────────────────────────────────────────

@Composable
private fun SessionList(
    saved: List<DjSavedSets.Entry>,
    recent: List<DjSavedSets.Entry>,
    onStart: (DjSavedSets.Entry) -> Unit,
    onSave: (DjSavedSets.Entry, Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(ItemGap)) {
        if (saved.isNotEmpty()) Label(stringResource(R.string.dj_sec_sessions_saved))
        saved.forEach { SessionRow(it, onStart, onSave) }
        if (recent.isNotEmpty()) Label(stringResource(R.string.dj_sec_sessions_recent))
        recent.take(5).forEach { SessionRow(it, onStart, onSave) }
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun SessionRow(entry: DjSavedSets.Entry, onStart: (DjSavedSets.Entry) -> Unit, onSave: (DjSavedSets.Entry, Boolean) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassRim, shape)
            .clickable { onStart(entry) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mosaic(entry.covers, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), ROW_ART_PX)
        Spacer(Modifier.width(Inset))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.reason ?: stringResource(R.string.dj_session_typed), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundIcon(
            if (entry.saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
            stringResource(if (entry.saved) R.string.dj_card_unsave else R.string.dj_card_save), Color.White.copy(alpha = 0.16f),
        ) { onSave(entry, !entry.saved) }
    }
}

// ── Words ───────────────────────────────────────────────────────────────────

@Composable
private fun sectionName(section: DjSection): String = stringResource(
    when (section) {
        DjSection.START -> R.string.dj_sec_start
        DjSection.FOR_YOU -> R.string.dj_sec_for_you
        DjSection.COME_BACK -> R.string.dj_sec_come_back
        DjSection.DISCOVER -> R.string.dj_sec_discover
        DjSection.MOMENT -> R.string.dj_sec_moment
        DjSection.EXPLORE -> R.string.dj_sec_explore
    },
)

@Composable
private fun moodName(mood: DjMood?): String = stringResource(
    when (mood) {
        DjMood.FOCUS -> R.string.dj_mood_focus
        DjMood.CHILL -> R.string.dj_mood_chill
        DjMood.ENERGY -> R.string.dj_mood_energy
        DjMood.ACOUSTIC, null -> R.string.dj_mood_acoustic
    },
)

private fun moodColor(mood: DjMood?): Color = when (mood) {
    DjMood.FOCUS -> Color(0xFF6FC8FF)
    DjMood.CHILL -> Color(0xFF6FE3C0)
    DjMood.ENERGY -> Color(0xFFFF8FA9)
    DjMood.ACOUSTIC -> Color(0xFFFFD86B)
    null -> Color.White
}

/** A set's title and the reason it is offered, in words: each kind says what the data behind it says. */
@Composable
private fun cardWords(card: DjSetCard): Pair<String, String?> {
    val n = card.number ?: 0
    val subject = card.subject.orEmpty()
    return when (card.kind) {
        DjCardKind.FOR_YOU -> stringResource(R.string.dj_for_you) to stringResource(R.string.dj_for_you_reason)
        DjCardKind.SURPRISE -> stringResource(R.string.dj_surprise) to stringResource(R.string.dj_surprise_reason)
        DjCardKind.MOOD -> moodName(card.mood) to stringResource(R.string.dj_songs_count, card.songs)
        DjCardKind.FAVORITES -> stringResource(R.string.dj_favorites) to stringResource(R.string.dj_favorites_reason, card.songs)
        DjCardKind.TOP_ARTIST -> subject to stringResource(R.string.dj_top_artist_reason, n)
        DjCardKind.HABITUAL_GENRE -> stringResource(R.string.dj_habitual, subject) to stringResource(R.string.dj_habitual_reason)
        DjCardKind.LOST_SONGS -> stringResource(R.string.dj_lost_songs) to stringResource(R.string.dj_lost_songs_reason, n)
        DjCardKind.LOST_ARTIST -> subject to stringResource(R.string.dj_lost_artist_reason, n)
        DjCardKind.LOST_ALBUM -> subject to stringResource(R.string.dj_lost_album_reason, n)
        DjCardKind.DISCOVER_NEAR -> stringResource(R.string.dj_discover_near) to stringResource(R.string.dj_discover_near_reason)
        DjCardKind.DISCOVER_ARTIST -> stringResource(R.string.dj_discover_artist, subject) to stringResource(R.string.dj_discover_artist_reason)
        DjCardKind.DISCOVER_FAR -> stringResource(R.string.dj_discover_far) to stringResource(R.string.dj_discover_far_reason)
        DjCardKind.MOMENT -> moodName(card.mood) to stringResource(
            when (n) {
                0 -> R.string.dj_moment_reason_morning
                1 -> R.string.dj_moment_reason_afternoon
                2 -> R.string.dj_moment_reason_evening
                else -> R.string.dj_moment_reason_night
            },
        )
        DjCardKind.THEME_ARTIST, DjCardKind.THEME_GENRE, DjCardKind.THEME_ALBUM -> subject to stringResource(R.string.dj_theme_reason, card.songs)
        // "the 90s", "the 2000s": a century's decades by their last two digits, the new ones whole.
        DjCardKind.THEME_DECADE -> stringResource(R.string.dj_theme_decade, if (n >= 2000) n else n % 100) to stringResource(R.string.dj_theme_reason, card.songs)
    }
}
