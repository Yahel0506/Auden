package dev.auden.dj

import java.util.Random

/**
 * Shapes a line for speaking so it sounds said rather than read.
 *
 * Text-to-speech reads punctuation as instruction: a comma is a small breath, a full stop a longer
 * one, and nothing else gives it rhythm. So a line is split into its sentences, each is spoken as
 * its own utterance with a pause between them whose length depends on the punctuation, and the
 * speed and pitch of each wanders slightly, the way a person's does, instead of repeating exactly.
 */
object DjSpeech {

    data class Utterance(val text: String, val rate: Float, val pitch: Float, val pauseAfterMs: Long)

    fun shape(line: String, seed: Long): List<Utterance> {
        val rng = Random(seed)
        val sentences = line.trim()
            .replace("—", ", ").replace(" - ", ", ")
            .split(Regex("(?<=[.!?…:])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return sentences.mapIndexed { i, sentence ->
            val last = i == sentences.lastIndex
            Utterance(
                text = sentence,
                // A touch under the engine's default, which is brisk; and never the same twice.
                rate = (BASE_RATE + (rng.nextFloat() - 0.5f) * 0.06f),
                pitch = (BASE_PITCH + (rng.nextFloat() - 0.5f) * 0.05f),
                pauseAfterMs = if (last) 0L else when (sentence.last()) {
                    '?', '!' -> 380L
                    ':' -> 220L
                    else -> 320L
                } + rng.nextInt(90),
            )
        }
    }

    private const val BASE_RATE = 0.95f
    private const val BASE_PITCH = 1.0f

    /** A stretch of a sentence, and whether it is one of the English names. */
    data class Span(val text: String, val english: Boolean)

    /** Cuts [text] around each occurrence of [names], keeping everything in order. */
    fun splitNames(text: String, names: List<String>): List<Span> {
        val usable = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        if (usable.isEmpty()) return listOf(Span(text, false))
        val edge = "(?<![\\p{L}\\p{N}])"
        val tail = "(?![\\p{L}\\p{N}])"
        val regex = Regex(edge + "(?:" + usable.joinToString("|") { Regex.escape(it) } + ")" + tail, RegexOption.IGNORE_CASE)
        val out = ArrayList<Span>()
        var at = 0
        for (m in regex.findAll(text)) {
            if (m.range.first > at) out += Span(text.substring(at, m.range.first), false)
            out += Span(m.value, true)
            at = m.range.last + 1
        }
        if (at < text.length) out += Span(text.substring(at), false)
        return out
    }

    private val englishWords = setOf(
        "the", "you", "your", "my", "me", "love", "baby", "night", "day", "heart", "dance", "girl", "boy", "man",
        "world", "life", "time", "sun", "moon", "fire", "dream", "dreams", "feel", "feeling", "want", "need", "all",
        "and", "of", "in", "on", "with", "for", "to", "is", "are", "it", "we", "i", "like", "just", "back", "home",
        "down", "up", "out", "no", "yes", "one", "two", "bad", "good", "big", "little", "blue", "black", "white",
        "red", "gold", "money", "party", "crazy", "dead", "die", "kiss", "hold", "run", "go", "get", "got", "let",
        "say", "tell", "know", "see", "look", "make", "take", "give", "stay", "stop", "never", "ever", "always",
        "again", "away", "break", "change", "city", "road", "river", "rain", "storm", "star", "stars", "sky",
        "angel", "devil", "god", "king", "queen", "street", "sweet", "sad", "happy", "lonely", "wild", "free",
        "young", "old", "new", "better", "best", "high", "low", "fast", "slow", "hot", "cold", "dark", "light",
        "bunny", "crip", "cry", "tears", "song", "songs", "music", "radio", "band", "club", "show", "play",
    )

    private val spanishWords = setOf(
        "de", "el", "la", "los", "las", "y", "que", "mi", "tu", "con", "por", "para", "sin", "un", "una", "del",
        "al", "es", "en", "se", "lo", "te", "me", "su", "mis", "tus", "como", "pero", "más", "muy",
    )

    /**
     * Whether a title or artist is English by the look of it. A guess from the words and the letter
     * patterns that only English has; it errs toward Spanish, since reading a Spanish name in English
     * is the worse mistake.
     */
    fun looksEnglish(name: String): Boolean {
        val words = Regex("[\\p{L}']+").findAll(name.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return false
        if (name.any { it.isLetter() && it.code > 127 }) return false
        if (words.any { it in spanishWords && it != "me" && it != "en" }) return false
        if (words.any { it in englishWords }) return true
        return words.any {
            Regex("th|ck|sh|wh|ough|ing$|'s$|[wk]|(bb|dd|ff|gg|mm|pp|tt|zz|ss)").containsMatchIn(it)
        }
    }
}
