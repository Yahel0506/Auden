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
 * the artists must share a credit, the version must be the same (a live take, an
 * acoustic one, a remix or a sped-up copy is another song, whatever its brackets
 * say), and the lengths, when both are known, must be within a few seconds.
 * Requiring all of it keeps a cover or a live take from passing as the studio
 * original, and a new release from being taken for the file with a similar name
 * the listener already has.
 */
object SongIdentity {
    private const val LENGTH_TOLERANCE_SEC = 3

    fun same(a: Song, b: Song): Boolean {
        if (a.videoId.isNotBlank() && a.videoId == b.videoId) return true
        if (a.localUri != null && a.localUri == b.localUri) return true
        val title = PlaylistMatcher.normalize(a.title)
        if (title.isEmpty() || title != PlaylistMatcher.normalize(b.title)) return false
        // "Song" and "Song (Live)" lose their brackets to the normaliser above: the version they name is not a credit.
        if (versionOf(a.title) != versionOf(b.title)) return false
        if (!sharesACredit(a.artist, b.artist)) return false
        val lengthA = seconds(a.durationText)
        val lengthB = seconds(b.durationText)
        return lengthA == null || lengthB == null || kotlin.math.abs(lengthA - lengthB) <= LENGTH_TOLERANCE_SEC
    }

    /**
     * The versions a title names, in its brackets or after a dash ("Song - Live at …", "Song (Sped Up)"): the ones that
     * make a different recording. A remaster, a deluxe edition, an explicit tag or a featured artist do not.
     */
    internal fun versionOf(title: String): Set<String> {
        val segments = ArrayList<String>()
        Regex("""[(\[](.*?)[)\]]""").findAll(title).forEach { segments += it.groupValues[1] }
        title.substringAfter(" - ", "").takeIf { it.isNotEmpty() }?.let { segments += it }
        val found = LinkedHashSet<String>()
        for (segment in segments) {
            for (word in Regex("""[\p{L}\p{N}]+""").findAll(java.text.Normalizer.normalize(segment, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase())) {
                val w = word.value
                VERSION_STEMS.firstOrNull { w.startsWith(it.first) }?.let { found += it.second }
            }
        }
        return found
    }

    private val VERSION_STEMS = listOf(
        "live" to "live", "acoust" to "acoustic", "remix" to "remix", "instrument" to "instrumental", "cover" to "cover",
        "karaoke" to "karaoke", "demo" to "demo", "sped" to "sped", "speed" to "sped", "slowed" to "slowed", "reverb" to "reverb",
        "nightcore" to "nightcore", "extended" to "extended", "radio" to "radio", "piano" to "piano", "unplug" to "unplugged",
        "session" to "session", "stripped" to "stripped", "orchestra" to "orchestral", "acapella" to "acapella", "cappella" to "acapella",
        "lofi" to "lofi", "bootleg" to "bootleg", "vip" to "vip",
    )

    /** Whether the two artist credits name someone in common: a credit is a whole name, not a part of one. */
    internal fun sharesACredit(a: String, b: String): Boolean {
        val creditsA = credits(a)
        val creditsB = credits(b)
        if (creditsA.isEmpty() || creditsB.isEmpty()) return false
        return creditsA.any { it in creditsB }
    }

    private fun credits(artist: String): Set<String> =
        artist.split(Regex("""\s*(?:,|&|\+|/|;|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\band\b|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .map { PlaylistMatcher.normalize(it).removePrefix("the ").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** "3:45" or "1:02:03" in seconds. */
    fun seconds(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
        return parts.fold(0) { acc, part -> acc * 60 + part }.takeIf { it > 0 }
    }
}
