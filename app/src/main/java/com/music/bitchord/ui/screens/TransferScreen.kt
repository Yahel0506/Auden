package com.music.bitchord.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.accentColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.ytmusic_logo
import com.music.bitchord.ui.components.IosSwitch
import com.music.bitchord.ui.icons.SfIcons
import dev.auden.library.Service
import dev.auden.transfer.ImportSelection
import dev.auden.transfer.ImportStage
import dev.auden.transfer.ImportStep
import dev.auden.transfer.RemoteArtist
import dev.auden.transfer.RemoteCollection
import dev.auden.transfer.Tally
import dev.auden.transfer.TransferMigrations
import dev.auden.transfer.TransferSession
import org.jetbrains.compose.resources.painterResource

/** [TransferScreen] with Spotify's connection read here, so the host page adds as little as it can. */
@Composable
fun TransferPage(
    ytConnected: Boolean,
    onConnectYt: () -> Unit,
    onConnectSpotify: () -> Unit,
    onOpenImportFlow: () -> Unit,
    contentPadding: PaddingValues,
) {
    val spotify by com.music.bitchord.data.settings.AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    TransferScreen(ytConnected, spotify.isNotBlank(), onConnectYt, onConnectSpotify, onOpenImportFlow, contentPadding)
}

/**
 * "Transfer your music": the one place the other services come in. A source is chosen, what it has is listed, the
 * listener picks what to bring over, and Auden copies it — reading from the service, writing only to Auden. Opened
 * from the account sheet.
 */
@Composable
fun TransferScreen(
    ytConnected: Boolean,
    spotifyConnected: Boolean,
    onConnectYt: () -> Unit,
    onConnectSpotify: () -> Unit,
    onOpenImportFlow: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by TransferSession.state.collectAsStateWithLifecycle()
    // Leaving the page puts a finished or half-chosen session away; a running import goes on without it.
    DisposableEffect(Unit) {
        TransferSession.pageOpen.value = true
        onDispose {
            TransferSession.pageOpen.value = false
            TransferSession.reset()
        }
    }
    (state as? TransferSession.State.Choosing)?.let { choosing ->
        // The grid scrolls on its own and the button stays under it, so this page is not the scrolling column below.
        TransferChooser(choosing.offer, contentPadding, modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.transfer_title))
        when (val s = state) {
            TransferSession.State.Idle -> Sources(ytConnected, spotifyConnected, onConnectYt, onConnectSpotify, onOpenImportFlow)
            is TransferSession.State.Loading -> Message(stringResource(R.string.transfer_loading), busy = true)
            // Its message is here; the button to try again is the one pinned at the foot (see TransferActionBar).
            is TransferSession.State.Failed -> Message(stringResource(R.string.transfer_failed))
            is TransferSession.State.Choosing -> Unit
            is TransferSession.State.Running -> Running(s)
            is TransferSession.State.Done -> Done(s)
        }
        // Room for the bar pinned at the foot.
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 140.dp))
    }
}

@Composable
private fun Sources(
    ytConnected: Boolean,
    spotifyConnected: Boolean,
    onConnectYt: () -> Unit,
    onConnectSpotify: () -> Unit,
    onOpenImportFlow: () -> Unit,
) {
    val likes by TransferMigrations.accountLikes.collectAsStateWithLifecycle()
    val connected = stringResource(R.string.transfer_connected)
    val connect = stringResource(R.string.transfer_connect)
    SettingsGroup(
        header = stringResource(R.string.transfer_sources_header),
        footer = stringResource(R.string.transfer_footer),
        topSpacing = 0.dp,
    ) {
        SettingsRow(
            iconPainter = painterResource(Res.drawable.ytmusic_logo),
            title = stringResource(R.string.transfer_yt_music),
            subtitle = if (ytConnected) connected else connect,
            onClick = { if (ytConnected) TransferSession.open(Service.YT_MUSIC) else onConnectYt() },
        )
        FullWidthDivider()
        SettingsRow(
            iconPainter = painterResource(Res.drawable.spotify_logo),
            title = stringResource(R.string.spotify),
            subtitle = if (spotifyConnected) connected else connect,
            onClick = { if (spotifyConnected) TransferSession.open(Service.SPOTIFY) else onConnectSpotify() },
        )
    }
    when (val l = likes) {
        is TransferMigrations.LikesState.Running -> Message(stringResource(R.string.transfer_likes_running), busy = true)
        is TransferMigrations.LikesState.Incomplete -> if (l.gaveUp) Message(stringResource(R.string.transfer_likes_incomplete))
        else -> Unit
    }
    SettingsGroup(header = stringResource(R.string.transfer_more_header)) {
        SettingsRow(
            icon = SfIcons.Download,
            title = stringResource(R.string.transfer_file_or_link),
            subtitle = stringResource(R.string.transfer_file_or_link_sub),
            onClick = onOpenImportFlow,
        )
    }
}

/**
 * What a service holds, as a grid of covers to tick: each playlist, album, artist and show is a tile with a check
 * in its corner, grouped by kind. The button that starts the transfer is pinned under the grid — in view however
 * far it is scrolled, and counting what is ticked.
 */
@Composable
internal fun TransferChooser(offer: TransferSession.Offer, contentPadding: PaddingValues = PaddingValues(), modifier: Modifier = Modifier) {
    val ticked by TransferSession.selection.collectAsStateWithLifecycle()
    val serviceName = stringResource(if (offer.service == Service.SPOTIFY) R.string.spotify else R.string.transfer_yt_music)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            // Clear of the button pinned over the foot of the page (TransferActionBar) and of the system's bar.
            bottom = androidx.compose.foundation.layout.WindowInsets.Companion.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SettingsPageTitle(stringResource(R.string.transfer_title))
                Text(
                    stringResource(R.string.transfer_choose_header, serviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
        item(key = "likes") {
            SelectTile(
                title = stringResource(R.string.transfer_likes),
                subtitle = null,
                imageUrl = null,
                icon = SfIcons.HeartFill,
                selected = ticked.likes,
                round = false,
                onClick = { TransferSession.toggleLikes() },
            )
        }
        collectionSection("playlists", R.string.transfer_playlists, TransferSession.Kind.PLAYLISTS, offer.playlists, ImportStep.PLAYLIST in offer.unreadable, ticked.playlists)
        collectionSection("albums", R.string.transfer_albums, TransferSession.Kind.ALBUMS, offer.albums, ImportStep.ALBUM in offer.unreadable, ticked.albums)
        artistSection("artists", R.string.transfer_artists, TransferSession.Kind.ARTISTS, offer.artists, ImportStep.ARTISTS in offer.unreadable, ticked.artists)
        if (offer.podcasts.isNotEmpty()) {
            artistSection("podcasts", R.string.transfer_podcasts, TransferSession.Kind.PODCASTS, offer.podcasts, false, ticked.podcasts)
        }
    }
}

/** A section's heading, with the one tap that ticks everything in it or clears it. */
@Composable
private fun SectionHeader(title: Int, total: Int, chosen: Int, onToggleAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (total > 0) {
            TextButton(onClick = onToggleAll) {
                Text(stringResource(if (chosen == total) R.string.transfer_select_none else R.string.transfer_select_all))
            }
        }
    }
}

private fun LazyGridScope.collectionSection(
    key: String,
    title: Int,
    kind: TransferSession.Kind,
    items: List<RemoteCollection>,
    unreadable: Boolean,
    ticked: Set<String>,
) {
    item(key = "$key:header", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(title, items.size, ticked.size) { TransferSession.toggleAll(kind, items.map { it.sourceId }) }
    }
    if (items.isEmpty()) {
        item(key = "$key:empty", span = { GridItemSpan(maxLineSpan) }) { EmptyNote(unreadable) }
        return
    }
    items(items, key = { "$key:${it.sourceId}" }) { item ->
        SelectTile(
            title = item.title,
            subtitle = item.subtitle.ifBlank { null },
            imageUrl = item.thumbnailUrl,
            icon = SfIcons.MusicNote,
            selected = item.sourceId in ticked,
            round = false,
            onClick = { TransferSession.toggle(kind, item.sourceId) },
        )
    }
}

private fun LazyGridScope.artistSection(
    key: String,
    title: Int,
    kind: TransferSession.Kind,
    items: List<RemoteArtist>,
    unreadable: Boolean,
    ticked: Set<String>,
) {
    item(key = "$key:header", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(title, items.size, ticked.size) { TransferSession.toggleAll(kind, items.map { it.id }) }
    }
    if (items.isEmpty()) {
        item(key = "$key:empty", span = { GridItemSpan(maxLineSpan) }) { EmptyNote(unreadable) }
        return
    }
    items(items, key = { "$key:${it.id}" }) { item ->
        SelectTile(
            title = item.name,
            subtitle = null,
            imageUrl = item.thumbnailUrl,
            icon = SfIcons.Person,
            selected = item.id in ticked,
            round = kind == TransferSession.Kind.ARTISTS,
            onClick = { TransferSession.toggle(kind, item.id) },
        )
    }
}

@Composable
private fun EmptyNote(unreadable: Boolean) {
    Text(
        stringResource(if (unreadable) R.string.transfer_unreadable else R.string.transfer_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** One thing to bring over: its cover, its name, and a check in the corner that fills when it is ticked. */
@Composable
private fun SelectTile(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    icon: ImageVector,
    selected: Boolean,
    round: Boolean,
    onClick: () -> Unit,
) {
    val accent = accentColor()
    val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
    val mark by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 1f, stiffness = 300f), label = "tile-check")
    Column(
        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(
                Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = mark }.border(3.dp, accent, shape))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Check, null, tint = Color.White,
                    modifier = Modifier.size(14.dp).graphicsLayer { alpha = mark; scaleX = 0.6f + 0.4f * mark; scaleY = 0.6f + 0.4f * mark },
                )
            }
        }
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.5.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (round) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        subtitle?.let {
            Text(
                it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The transfer under way: a ring that fills with the real share done, the thing being brought over and what is
 * happening to it (reading, finding songs, saving), and which of the things ticked this is. The bar pinned at the
 * foot (see TransferActionBar) moves with the same number.
 */
@Composable
private fun Running(state: TransferSession.State.Running) {
    val accent = accentColor()
    val p = state.progress
    val fraction by animateFloatAsState(state.fraction, spring(dampingRatio = 1f, stiffness = 300f), label = "transfer-fraction")
    // A slow breath behind the ring, so a long read of a big library still looks alive.
    val breath = rememberInfiniteTransition(label = "transfer-breath")
    val glow by breath.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "transfer-glow",
    )
    val step = when (p?.step) {
        ImportStep.LIKES -> stringResource(R.string.transfer_step_likes)
        ImportStep.PLAYLIST -> stringResource(R.string.transfer_playlists)
        ImportStep.ALBUM -> stringResource(R.string.transfer_albums)
        ImportStep.ARTISTS -> stringResource(R.string.transfer_artists)
        null -> stringResource(R.string.transfer_running)
    }
    val stages = listOf(
        ImportStage.READING to R.string.transfer_stage_reading,
        ImportStage.MATCHING to R.string.transfer_stage_matching,
        ImportStage.STORING to R.string.transfer_stage_storing,
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(224.dp)
                    .graphicsLayer { alpha = glow }
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.5f), Color.Transparent)), CircleShape),
            )
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(172.dp),
                strokeWidth = 10.dp,
                color = accent,
                trackColor = accent.copy(alpha = 0.16f),
                strokeCap = StrokeCap.Round,
            )
            Text(
                "${(fraction * 100).toInt()}%",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(step, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        Text(
            p?.title.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.transfer_unit_of, state.unit, state.units),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { index, (stage, label) ->
                if (index > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                val on = p?.stage == stage
                Text(
                    stringResource(label),
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun Done(state: TransferSession.State.Done) {
    val r = state.report
    SettingsGroup(header = stringResource(R.string.transfer_done_title), topSpacing = 0.dp) {
        val rows = listOf(
            R.string.transfer_likes to r.likes,
            R.string.transfer_playlists to r.playlists,
            R.string.transfer_albums to r.albums,
            R.string.transfer_artists to r.artists,
        ).filter { (_, t) -> t != Tally() }
        rows.forEachIndexed { index, (label, t) ->
            if (index > 0) FullWidthDivider()
            SettingsRow(title = stringResource(label), subtitle = stringResource(R.string.transfer_done_tally, t.added, t.skipped, t.unmatched))
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.transfer_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
    if (!r.complete) Message(stringResource(R.string.transfer_done_incomplete, r.failed.size))
}

@Composable
private fun Message(text: String, busy: Boolean = false) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
