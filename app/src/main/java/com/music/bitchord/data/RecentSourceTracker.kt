package com.music.bitchord.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.RecentSource

/**
 * Keeps what was played lately, by where it was played from: a song played out of a playlist is the playlist's, not its
 * album's — the page the song was opened from is the one that is remembered, whatever it is.
 */
@Composable
fun TrackRecentSources(song: Song?, isPlaying: Boolean) {
    LaunchedEffect(song?.playbackSourceId, song?.playbackSourceType, isPlaying) {
        val s = song ?: return@LaunchedEffect
        if (!isPlaying || s.playbackSourceType != PlaybackSourceType.BROWSE) return@LaunchedEffect
        val id = s.playbackSourceId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val title = (s.playbackSource ?: s.albumName)?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        AppSettings.recordRecentSource(RecentSource(id, title, s.thumbnailUrl))
    }
}
