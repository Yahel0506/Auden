package com.music.bitchord.ui.screens

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
            is TransferSession.State.Choosing -> Choose(s.offer)
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

@Composable
private fun Choose(offer: TransferSession.Offer) {
    val context = LocalContext.current
    var likes by remember(offer) { mutableStateOf(false) }
    val playlists = remember(offer) { mutableStateListOf<String>() }
    val albums = remember(offer) { mutableStateListOf<String>() }
    val artists = remember(offer) { mutableStateListOf<String>() }
    val podcasts = remember(offer) { mutableStateListOf<String>() }
    val serviceName = stringResource(if (offer.service == Service.SPOTIFY) R.string.spotify else R.string.transfer_yt_music)
    SettingsGroup(header = stringResource(R.string.transfer_choose_header, serviceName), topSpacing = 0.dp) {
        SettingsRow(
            title = stringResource(R.string.transfer_likes),
            subtitle = stringResource(R.string.transfer_likes_sub),
            onClick = { likes = !likes },
            trailing = { IosSwitch(checked = likes, onCheckedChange = null) },
        )
    }
    CollectionGroup(R.string.transfer_playlists, offer.playlists, ImportStep.PLAYLIST in offer.unreadable, playlists)
    CollectionGroup(R.string.transfer_albums, offer.albums, ImportStep.ALBUM in offer.unreadable, albums)
    ArtistGroup(R.string.transfer_artists, offer.artists, ImportStep.ARTISTS in offer.unreadable, artists)
    if (offer.podcasts.isNotEmpty()) ArtistGroup(R.string.transfer_podcasts, offer.podcasts, false, podcasts)
    val any = likes || playlists.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty() || podcasts.isNotEmpty()
    ActionButton(stringResource(R.string.transfer_start), enabled = any) {
        val chosen = (offer.playlists + offer.albums).filter { it.sourceId in playlists || it.sourceId in albums }
        TransferSession.start(
            context, offer.service,
            ImportSelection(
                likes = likes,
                collections = chosen,
                // Podcast shows are followed the way artists are, and shelved apart by their page's id.
                artists = (offer.artists.filter { it.id in artists } + offer.podcasts.filter { it.id in podcasts }),
            ),
        )
    }
    ActionButton(stringResource(R.string.transfer_cancel), primary = false) { TransferSession.reset() }
}

@Composable
private fun CollectionGroup(title: Int, items: List<RemoteCollection>, unreadable: Boolean, chosen: MutableList<String>) {
    SettingsGroup(header = stringResource(title)) {
        if (items.isEmpty()) {
            Text(
                stringResource(if (unreadable) R.string.transfer_unreadable else R.string.transfer_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            return@SettingsGroup
        }
        val all = chosen.size == items.size
        SettingsRow(
            title = stringResource(if (all) R.string.transfer_select_none else R.string.transfer_select_all),
            onClick = { chosen.clear(); if (!all) chosen.addAll(items.map { it.sourceId }) },
        )
        items.forEach { item ->
            FullWidthDivider()
            SettingsRow(
                title = item.title,
                subtitle = item.subtitle.ifBlank { null },
                onClick = { if (item.sourceId in chosen) chosen.remove(item.sourceId) else chosen.add(item.sourceId) },
                trailing = { IosSwitch(checked = item.sourceId in chosen, onCheckedChange = null) },
            )
        }
    }
}

@Composable
private fun ArtistGroup(title: Int, items: List<RemoteArtist>, unreadable: Boolean, chosen: MutableList<String>) {
    SettingsGroup(header = stringResource(title)) {
        if (items.isEmpty()) {
            Text(
                stringResource(if (unreadable) R.string.transfer_unreadable else R.string.transfer_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            return@SettingsGroup
        }
        val all = chosen.size == items.size
        SettingsRow(
            title = stringResource(if (all) R.string.transfer_select_none else R.string.transfer_select_all),
            onClick = { chosen.clear(); if (!all) chosen.addAll(items.map { it.id }) },
        )
        items.forEach { item ->
            FullWidthDivider()
            SettingsRow(
                title = item.name,
                onClick = { if (item.id in chosen) chosen.remove(item.id) else chosen.add(item.id) },
                trailing = { IosSwitch(checked = item.id in chosen, onCheckedChange = null) },
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
