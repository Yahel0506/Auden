package dev.auden.library

import com.music.bitchord.data.model.Song
import dev.auden.library.io.PlaylistMatcher

/**
 * Whether two [Song] rows are the same recording, whichever service or file
 * each came from.
 *
 * The same song has many ids — the album track, the music video, a file on the
 * device, the same file once downloaded — so ids only settle the easy cases.
 * Otherwise the metadata decides: the title must agree once accents, case,
 * punctuation and bracketed credits ("(Remastered)", "[feat. X]") are ignored,
 * the artists must overlap, and the lengths, when both are known, must be
 * within a few seconds. Requiring all three keeps a cover or a live take from
 * passing as the studio original.
 */
object SongIdentity {
    private const val LENGTH_TOLERANCE_SEC = 3

    fun same(a: Song, b: Song): Boolean {
        if (a.videoId.isNotBlank() && a.videoId == b.videoId) return true
        if (a.localUri != null && a.localUri == b.localUri) return true
        val title = PlaylistMatcher.normalize(a.title)
        if (title.isEmpty() || title != PlaylistMatcher.normalize(b.title)) return false
        val artistA = PlaylistMatcher.normalize(a.artist)
        val artistB = PlaylistMatcher.normalize(b.artist)
        if (artistA.isEmpty() || artistB.isEmpty()) return false
        if (artistA != artistB && !artistA.contains(artistB) && !artistB.contains(artistA)) return false
        val lengthA = seconds(a.durationText)
        val lengthB = seconds(b.durationText)
        return lengthA == null || lengthB == null || kotlin.math.abs(lengthA - lengthB) <= LENGTH_TOLERANCE_SEC
    }

    /** "3:45" or "1:02:03" in seconds. */
    fun seconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        return parts.fold(0) { acc, part -> acc * 60 + part }.takeIf { it > 0 }
    }
}
