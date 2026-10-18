package dev.auden.library.ident

/**
 * Reads a YouTube video id out of a file name, the way downloaders that keep
 * the id in the name write it: `Artist - Title [dQw4w9WgXcQ].m4a`, or a
 * `v=` / `youtu.be/` form anywhere in it.
 *
 * Guarded hard against false positives, because a wrong id would put a file
 * under another song entirely: an 11-character bracket is only taken when it is
 * the last thing before the extension, ends in a character a real id can end
 * in, and does not read as an ordinary word, a counter, or a camera file
 * ("Bonus_Track", "REC00010004", "VID20240104").
 */
object YoutubeIdInName {
    private val idChars = Regex("^[A-Za-z0-9_-]{11}$")
    private val bracketed = Regex("\\[([A-Za-z0-9_-]{11})\\]\\.[A-Za-z0-9]{2,5}$")
    private val urlForm = Regex("(?:v=|youtu\\.be/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])")

    /** An 11-character id encodes 66 bits in 64, so its last character carries only four of its six. */
    private const val LAST_CHARS = "AEIMQUYcgkosw048"

    private val wordLike = Regex("^(?:[A-Za-z][a-z]*|[A-Z]+|[0-9]+)$")
    private val counter = Regex("^(?:[A-Za-z]+[0-9]+|[0-9]+[A-Za-z]+)$")

    fun extract(fileName: String): String? {
        urlForm.find(fileName)?.groupValues?.get(1)?.takeIf(::plausible)?.let { return it }
        val id = bracketed.find(fileName)?.groupValues?.get(1) ?: return null
        return id.takeIf { plausible(it) && !looksLikeText(it) }
    }

    private fun plausible(id: String) = idChars.matches(id) && id.last() in LAST_CHARS

    private fun looksLikeText(id: String): Boolean {
        if (counter.matches(id)) return true
        val parts = id.split('_', '-').filter { it.isNotEmpty() }
        // A real id nearly always has a digit or a capital in the middle of a word; text has neither.
        return parts.isNotEmpty() && parts.all { wordLike.matches(it) }
    }
}
