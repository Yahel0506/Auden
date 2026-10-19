package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.data.spotify.SpotifyAlbum
import com.music.bitchord.data.spotify.SpotifyArtist
import com.music.bitchord.data.spotify.SpotifyException
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import dev.auden.library.spotify.SpotifyLibraryCache
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.Res
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack

/**
 * The signed-in Spotify account's library: playlists, saved albums and followed artists. Each opens as an ordinary
 * page of this app — a playlist is read from Spotify and matched to YouTube Music, an album or artist is
 * found on YouTube Music — so this screen is only the way in.
 *
 * What was seen last time is shown at once and refreshed behind it, so the page works offline and never opens
 * empty; a failure says what to do about it rather than quoting an error.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenAlbum: (SpotifyAlbum) -> Unit,
    onOpenArtist: (SpotifyArtist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val copies by dev.auden.library.LocalPlaylists.playlists.collectAsStateWithLifecycle()
    val syncing by dev.auden.library.CloudSaver.saving.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var playlists by remember { mutableStateOf(SpotifyLibraryCache.playlists(context)) }
    var albums by remember { mutableStateOf(SpotifyLibraryCache.albums(context)) }
    var artists by remember { mutableStateOf(SpotifyLibraryCache.artists(context)) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SpotifyException?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val spotifyCopies = copies.count { it.sourceId?.startsWith(SPOTIFY_PAGE_PREFIX) == true }

    LaunchedEffect(cookie, reload) {
        if (cookie.isBlank()) {
            failure = null
            return@LaunchedEffect
        }
        loading = true
        failure = null
        // Each list on its own: an account with no followed artists, or a response in a shape not understood, must
        // not take the playlists down with it.
        val errors = mutableListOf<Throwable>()
        runCatching { SpotifyLibrary.playlists() }.onSuccess { playlists = it; SpotifyLibraryCache.savePlaylists(context, it) }.onFailure(errors::add)
        runCatching { SpotifyLibrary.albums() }.onSuccess { albums = it; SpotifyLibraryCache.saveAlbums(context, it) }
        runCatching { SpotifyLibrary.artists() }.onSuccess { artists = it; SpotifyLibraryCache.saveArtists(context, it) }
        failure = errors.firstOrNull()?.let { it as? SpotifyException ?: SpotifyException(SpotifyException.Kind.UNKNOWN, it.message.orEmpty()) }
        loading = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLogin) {
                        Text(stringResource(R.string.spotify_sign_in))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenTokenSetup) {
                        Text(stringResource(R.string.spotify_use_spdc_token))
                    }
                }
            }
            return@LazyColumn
        }

        item {
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.playlists),
                    stringResource(R.string.albums),
                    stringResource(R.string.artists),
                ),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
        failure?.let { problem ->
            item {
                SpotifyProblem(
                    problem = problem,
                    onRetry = { reload++ },
                    onSignIn = onOpenLogin,
                )
            }
        }
        if (loading && (tab == 0 && playlists.isEmpty() || tab == 1 && albums.isEmpty() || tab == 2 && artists.isEmpty())) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        when (tab) {
            0 -> {
                if (spotifyCopies > 0) {
                    item {
                        SyncRow(
                            copies = spotifyCopies,
                            busy = syncing.any { it.startsWith(SPOTIFY_PAGE_PREFIX) },
                            onClick = { scope.launch { dev.auden.library.CloudSaver.syncSpotifyCopies(context) } },
                        )
                    }
                }
                items(playlists, key = { "p-${it.id}" }) { item ->
                    LibraryRow(
                        title = item.name,
                        subtitle = item.owner.orEmpty(),
                        imageUrl = item.imageUrl,
                        round = false,
                        onClick = { onOpenPlaylist(item) },
                    )
                }
            }
            1 -> {
                if (!loading && albums.isEmpty() && failure == null) item { EmptyNote(stringResource(R.string.spotify_empty_albums)) }
                items(albums, key = { "a-${it.id}" }) { item ->
                    LibraryRow(title = item.name, subtitle = item.artist, imageUrl = item.imageUrl, round = false, onClick = { onOpenAlbum(item) })
                }
            }
            else -> {
                if (!loading && artists.isEmpty() && failure == null) item { EmptyNote(stringResource(R.string.spotify_empty_artists)) }
                items(artists, key = { "r-${it.id}" }) { item ->
                    LibraryRow(title = item.name, subtitle = "", imageUrl = item.imageUrl, round = true, onClick = { onOpenArtist(item) })
                }
            }
        }
    }
}

/** What went wrong, in words, with the one thing that can help: sign in again, or try again. */
@Composable
private fun SpotifyProblem(problem: SpotifyException, onRetry: () -> Unit, onSignIn: () -> Unit) {
    val needsSignIn = problem.kind == SpotifyException.Kind.SIGNED_OUT || problem.kind == SpotifyException.Kind.EXPIRED
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(
                when (problem.kind) {
                    SpotifyException.Kind.SIGNED_OUT -> R.string.spotify_err_signed_out
                    SpotifyException.Kind.EXPIRED -> R.string.spotify_err_expired
                    SpotifyException.Kind.RATE_LIMITED -> R.string.spotify_err_rate_limited
                    SpotifyException.Kind.NETWORK -> R.string.spotify_err_network
                    SpotifyException.Kind.UNKNOWN -> R.string.spotify_err_unknown
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = if (needsSignIn) onSignIn else onRetry) {
            Text(stringResource(if (needsSignIn) R.string.spotify_sign_in else R.string.retry))
        }
    }
}

@Composable
private fun SyncRow(copies: Int, busy: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            else Icon(Icons.Rounded.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.spotify_sync_copies), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(R.string.spotify_sync_copies_count, copies),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun LibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    round: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
