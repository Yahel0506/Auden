package com.music.bitchord.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.download.Downloads
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage
import dev.auden.library.db.AudenDb
import dev.auden.library.spotify.SpotifyLibraryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Library's three ways in that are not shelves: the Replay banner, the way into the song cache, and the
// playlists the app makes itself. Each is one decorated object of its own, so the top of the page is three things
// to look at and not a wallet of cards and a list of folders.

private val BannerShape = RoundedCornerShape(14.dp)
private val BackupAccent = Color(0xFF0A84FF)

/**
 * Replay as a slim banner in the manner of Apple Music's Replay: a small caps caption, a bold line, over a golden gradient. Opens the numbers.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    val lead = cards.firstOrNull()
    if (loading) {
        Box(
            Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp).fillMaxWidth().height(72.dp)
                .clip(BannerShape).background(Color.White.copy(alpha = 0.08f)),
        )
        return
    }
    if (lead == null) return
    Box(
        Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
            .fillMaxWidth()
            .height(72.dp)
            .clip(BannerShape)
            .background(Brush.linearGradient(listOf(Color(0xFFF2B84B), Color(0xFFD98E2B), Color(0xFF9C5A14))))
            .clickable { onOpenReplay(ReplayStoryPage.MINUTES) },
    ) {
        // The bars of a listening, rising to the right.
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 46.dp).height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(0.35f, 0.55f, 0.4f, 0.8f, 0.6f, 1f, 0.7f).forEach { h ->
                Box(Modifier.width(5.dp).fillMaxHeight(h).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.55f)))
            }
        }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
            Text(
                stringResource(R.string.replay).uppercase(),
                color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            )
            Text(
                "${lead.value} ${lead.label.substringBefore(' ').lowercase()}",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            BitChordIcons.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(20.dp),
        )
    }
}

/**
 * "Respaldo musical": a card into every song the cache holds, in the manner of Apple Music's cards — a red caps
 * caption, a big bold title, a grey line, a Play pill, and the covers of the songs as a mosaic at the right.
 */
@Composable
fun LibraryBackupBanner(onOpen: (ShelfItem) -> Unit, onPlay: () -> Unit) {
    val title = stringResource(R.string.music_backup)
    val subtitle = stringResource(R.string.music_backup_subtitle)
    var covers by remember { mutableStateOf<List<String?>>(emptyList()) }
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val songs = withContext(Dispatchers.IO) { AudioCache.cachedSongs() }
        count = songs.size
        covers = songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(4)
    }
    Row(
        Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
            .fillMaxWidth()
            .clip(BannerShape)
            .background(Color(0xFF1C1C1E))
            .clickable {
                onOpen(ShelfItem(title = title, subtitle = subtitle, thumbnailUrl = null, videoId = null, browseId = CACHE_FOLDER_BROWSE_ID))
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.library_backup_caption).uppercase(),
                color = BackupAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            )
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (count == 0) subtitle else if (count == 1) stringResource(R.string.library_songs_count_one) else stringResource(R.string.library_songs_count, count),
                color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onPlay)
                    .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = BackupAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.play), color = BackupAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(12.dp))
        // The covers as a mosaic, like a playlist's artwork.
        Column(Modifier.size(104.dp).clip(RoundedCornerShape(8.dp)), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            for (r in 0..1) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (c in 0..1) {
                        val url = covers.getOrNull(r * 2 + c)
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(alpha = 0.1f))) {
                            if (url != null) {
                                AsyncImage(
                                    model = url.artworkAt(ROW_ART_PX), contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The app's own folders as the Library's shelf items, for a row of playlists like any other: Downloads and Local music,
 * each with how many songs it holds. The cover each card and each page wears is [com.music.bitchord.ui.components.FolderCover]'s.
 */
@Composable
fun rememberFolderItems(): List<ShelfItem> {
    val context = LocalContext.current
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    var local by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        local = withContext(Dispatchers.IO) { AudenDb.get(context.applicationContext).tracks().all().count { it.localUri != null } }
    }
    fun songs(n: Int) = if (n == 1) context.getString(R.string.library_songs_count_one) else context.getString(R.string.library_songs_count, n)
    return listOf(
        ShelfItem(title = stringResource(R.string.downloads), subtitle = songs(saved.size), thumbnailUrl = null, videoId = null, browseId = "local:downloads"),
        ShelfItem(title = stringResource(R.string.local_music), subtitle = songs(local), thumbnailUrl = null, videoId = null, browseId = "local:all"),
    )
}

/**
 * The Spotify account's playlists as the Library's shelf items — the cache at once, refreshed behind it, so the row
 * is there when the page opens. Empty while no account is connected.
 */
@Composable
fun rememberSpotifyShelf(): List<ShelfItem> {
    val context = LocalContext.current
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var playlists by remember { mutableStateOf(SpotifyLibraryCache.playlists(context)) }
    LaunchedEffect(cookie) {
        if (cookie.isBlank()) return@LaunchedEffect
        runCatching { SpotifyLibrary.playlists() }.onSuccess {
            playlists = it
            SpotifyLibraryCache.savePlaylists(context, it)
        }
    }
    if (cookie.isBlank()) return emptyList()
    val subtitle = stringResource(R.string.spotify)
    return playlists.map { playlist ->
        ShelfItem(
            title = playlist.name,
            subtitle = playlist.owner ?: subtitle,
            thumbnailUrl = playlist.imageUrl,
            videoId = null,
            browseId = SPOTIFY_PAGE_PREFIX + playlist.id,
        )
    }
}
