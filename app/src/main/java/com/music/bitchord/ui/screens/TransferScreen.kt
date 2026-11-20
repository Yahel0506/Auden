package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
    DisposableEffect(Unit) { onDispose { TransferSession.reset() } }
    (state as? TransferSession.State.Choosing)?.let { choosing ->
        // The grid scrolls on its own and the button stays under it, so this page is not the scrolling column below.
        Choose(choosing.offer, contentPadding, modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.transfer_title))
        when (val s = state) {
            TransferSession.State.Idle -> Sources(ytConnected, spotifyConnected, onConnectYt, onConnectSpotify, onOpenImportFlow)
            is TransferSession.State.Loading -> Message(stringResource(R.string.transfer_loading), busy = true)
            is TransferSession.State.Failed -> {
                Message(stringResource(R.string.transfer_failed))
                ActionButton(stringResource(R.string.transfer_retry)) { TransferSession.open(s.service) }
            }
            is TransferSession.State.Choosing -> Unit
            is TransferSession.State.Running -> Running(s)
            is TransferSession.State.Done -> Done(s)
        }
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
            icon = SfIcons.LibraryMusic,
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
private fun Choose(offer: TransferSession.Offer, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var likes by remember(offer) { mutableStateOf(false) }
    val playlists = remember(offer) { mutableStateListOf<String>() }
    val albums = remember(offer) { mutableStateListOf<String>() }
    val artists = remember(offer) { mutableStateListOf<String>() }
    val podcasts = remember(offer) { mutableStateListOf<String>() }
    val count = (if (likes) 1 else 0) + playlists.size + albums.size + artists.size + podcasts.size
    val serviceName = stringResource(if (offer.service == Service.SPOTIFY) R.string.spotify else R.string.transfer_yt_music)
    val barHeight = 76.dp
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + barHeight,
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
                    selected = likes,
                    round = false,
                    onClick = { likes = !likes },
                )
            }
            collectionSection("playlists", R.string.transfer_playlists, offer.playlists, ImportStep.PLAYLIST in offer.unreadable, playlists)
            collectionSection("albums", R.string.transfer_albums, offer.albums, ImportStep.ALBUM in offer.unreadable, albums)
            artistSection("artists", R.string.transfer_artists, offer.artists, ImportStep.ARTISTS in offer.unreadable, artists)
            if (offer.podcasts.isNotEmpty()) artistSection("podcasts", R.string.transfer_podcasts, offer.podcasts, false, podcasts)
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = contentPadding.calculateBottomPadding())
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { TransferSession.reset() }) { Text(stringResource(R.string.transfer_cancel)) }
            Button(
                onClick = {
                    val chosen = (offer.playlists + offer.albums).filter { it.sourceId in playlists || it.sourceId in albums }
                    TransferSession.start(
                        context, offer.service,
                        ImportSelection(
                            likes = likes,
                            collections = chosen,
                            // Podcast shows are followed the way artists are, and shelved apart by their page's id.
                            artists = offer.artists.filter { it.id in artists } + offer.podcasts.filter { it.id in podcasts },
                        ),
                    )
                },
                enabled = count > 0,
                modifier = Modifier.weight(1f),
            ) { Text(if (count > 0) stringResource(R.string.transfer_start_count, count) else stringResource(R.string.transfer_start)) }
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
    items: List<RemoteCollection>,
    unreadable: Boolean,
    chosen: MutableList<String>,
) {
    item(key = "$key:header", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(title, items.size, chosen.size) {
            val all = chosen.size == items.size
            chosen.clear()
            if (!all) chosen.addAll(items.map { it.sourceId })
        }
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
            selected = item.sourceId in chosen,
            round = false,
            onClick = { if (item.sourceId in chosen) chosen.remove(item.sourceId) else chosen.add(item.sourceId) },
        )
    }
}

private fun LazyGridScope.artistSection(
    key: String,
    title: Int,
    items: List<RemoteArtist>,
    unreadable: Boolean,
    chosen: MutableList<String>,
) {
    item(key = "$key:header", span = { GridItemSpan(maxLineSpan) }) {
        SectionHeader(title, items.size, chosen.size) {
            val all = chosen.size == items.size
            chosen.clear()
            if (!all) chosen.addAll(items.map { it.id })
        }
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
            selected = item.id in chosen,
            round = key == "artists",
            onClick = { if (item.id in chosen) chosen.remove(item.id) else chosen.add(item.id) },
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

@Composable
private fun Running(state: TransferSession.State.Running) {
    val p = state.progress
    val step = when (p?.step) {
        ImportStep.LIKES -> stringResource(R.string.transfer_step_likes)
        ImportStep.PLAYLIST -> stringResource(R.string.transfer_playlists)
        ImportStep.ALBUM -> stringResource(R.string.transfer_albums)
        ImportStep.ARTISTS -> stringResource(R.string.transfer_artists)
        null -> stringResource(R.string.transfer_running)
    }
    val stage = when (p?.stage) {
        ImportStage.READING -> stringResource(R.string.transfer_stage_reading)
        ImportStage.MATCHING -> stringResource(R.string.transfer_stage_matching)
        ImportStage.STORING -> stringResource(R.string.transfer_stage_storing)
        null -> ""
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(step, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            listOfNotNull(stage.takeIf { it.isNotBlank() }, p?.title).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p != null && p.total > 0) {
            LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
    ActionButton(stringResource(R.string.transfer_done_button)) { TransferSession.reset() }
}

@Composable
private fun Message(text: String, busy: Boolean = false) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (primary) androidx.compose.material3.ButtonDefaults.buttonColors()
        else androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) { Text(label) }
}
