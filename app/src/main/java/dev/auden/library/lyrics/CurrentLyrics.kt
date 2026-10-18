package dev.auden.library.lyrics

import com.music.bitchord.data.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the player's lyrics provider sheet knows about the song on screen: which providers' lyrics are
 * downloaded for it, which are being downloaded, and the two things it can do about it.
 *
 * Kept as a small bridge because the sheet lives in the shared UI module and the view model that owns the
 * playing song's lookup lives in the app; the view model fills it in.
 */
object CurrentLyrics {
    private val _stored = MutableStateFlow<Set<LyricsSource>>(emptySet())
    private val _downloading = MutableStateFlow<Set<LyricsSource>>(emptySet())

    /** Providers whose lyrics for the playing song are downloaded. */
    val stored: StateFlow<Set<LyricsSource>> = _stored.asStateFlow()

    /** Providers being fetched for download right now. */
    val downloading: StateFlow<Set<LyricsSource>> = _downloading.asStateFlow()

    @Volatile var onDownload: (LyricsSource) -> Unit = {}
    @Volatile var onRemove: (LyricsSource) -> Unit = {}

    fun setStored(value: Set<LyricsSource>) { _stored.value = value }

    fun setDownloading(source: LyricsSource, on: Boolean) {
        _downloading.value = if (on) _downloading.value + source else _downloading.value - source
    }
}
