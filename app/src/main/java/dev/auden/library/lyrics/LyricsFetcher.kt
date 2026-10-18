package dev.auden.library.lyrics

import com.music.bitchord.data.lyrics.LyricsArtifact
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSerializer
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import dev.auden.library.SongIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Asks one lyrics provider, by name, for a song — whether or not the song is playing or downloaded. */
object LyricsFetcher {
    private const val TIMEOUT_MS = 30_000L

    /** The lyrics [provider] has for [song], or null when it has none or cannot be reached. */
    suspend fun fetch(song: Song, provider: LyricsSource): LyricsArtifact? {
        val durationMs = (SongIdentity.seconds(song.durationText) ?: return null) * 1000L
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                LyricsRepository.lyrics(
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.artist,
                    durationMs = durationMs,
                    album = song.albumName,
                    sources = setOf(provider),
                    order = listOf(provider),
                    prioritizeSyllableSync = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } ?: return null
        val artifact = result.artifact ?: return null
        if (artifact.lines.none { it.text.isNotBlank() }) return null
        if (artifact.content.length > LyricsSerializer.MAX_LYRICS_CHARS) return null
        return artifact
    }
}
