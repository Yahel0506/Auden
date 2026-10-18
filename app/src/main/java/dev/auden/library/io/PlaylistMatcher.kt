package dev.auden.library.io

import dev.auden.library.db.TrackEntity
import java.text.Normalizer

/**
 * Finds the library track a playlist line means.
 *
 * Tried from most to least certain, stopping at the first hit:
 *  1. the video id, for files Auden wrote itself;
 *  2. the exact path;
 *  3. the same file name somewhere else (a playlist moved between devices),
 *     choosing the candidate whose path shares the longest tail with the line;
 *  4. title and artist (and length, when both sides know it) after normalising.
 */
class PlaylistMatcher(library: List<TrackEntity>) {
    private val byVideoId = library.filter { !it.videoId.isNullOrBlank() }.associateBy { it.videoId!! }
    private val byPath = library.filter { it.path != null }.associateBy { it.path!!.lowercase() }
    private val byFileName = library.filter { it.path != null }.groupBy { Paths.fileName(it.path!!).lowercase() }
    private val byTitle = library.groupBy { normalize(it.title) }

    fun match(entry: PlaylistEntry): TrackEntity? {
        entry.videoId?.let { byVideoId[it] }?.let { return it }
        entry.path?.takeIf { !entry.isUrl }?.let { path ->
            byPath[path.lowercase()]?.let { return it }
            byFileName[Paths.fileName(path).lowercase()]?.let { candidates ->
                return candidates.maxBy { commonTail(it.path!!.lowercase(), path.lowercase()) }
            }
        }
        val title = entry.title?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        val artist = entry.artist?.let(::normalize)
        return byTitle[title].orEmpty().firstOrNull { track ->
            (artist == null || normalize(track.artist).let { it == artist || it.contains(artist) || artist.contains(it) }) &&
                lengthAgrees(track, entry)
        }
    }

    private fun lengthAgrees(track: TrackEntity, entry: PlaylistEntry): Boolean {
        val want = entry.durationSec ?: return true
        val have = track.durationMs?.div(1000.0) ?: return true
        return kotlin.math.abs(want - have) <= 2.0
    }

    /** How many trailing characters two paths share. */
    private fun commonTail(a: String, b: String): Int {
        var i = a.length
        var j = b.length
        while (i > 0 && j > 0 && a[i - 1] == b[j - 1]) { i--; j-- }
        return a.length - i
    }

    companion object {
        /** Lower-case, accent-free, without bracketed credits or punctuation. */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[(\\[].*?[)\\]]"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
    }
}
