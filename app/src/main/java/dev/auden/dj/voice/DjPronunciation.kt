package dev.auden.dj.voice

import java.text.Normalizer
import java.util.Locale

/**
 * What the DJ's voice is given in place of an artist's name, so that it says it the way people do.
 *
 * A text-to-speech model reads what it is handed and cannot know that NSQK is "nescuic" or that MGMT is spelled out.
 * So the name is respelled before it goes to the voice: first from a dictionary of names it is known to get wrong
 * ([DjPronunciation.Lexicon], bundled with the app), then, for any other name that is plainly not a word, by rules.
 * The words on the screen are never changed: only the line sent to the voice is.
 */
object DjPronunciation {

    /** The dictionary: a name, folded ([fold]), to how each voice should say it. */
    class Lexicon(private val entries: Map<String, Entry>) {
        class Entry(val name: String, val es: String?, val en: String?) {
            fun forLanguage(language: String): String? = if (language == "es") es else en
        }

        val size: Int get() = entries.size

        /** How [language]'s voice should say [name], or null when the dictionary has nothing for it. */
        fun lookup(name: String, language: String): String? = entries[fold(name)]?.forLanguage(language)

        fun knows(name: String): Boolean = fold(name) in entries

        /**
         * How each name is said, folded and without spaces, back to the name: so that what someone says to the DJ
         * ("nescuic") finds the artist the dictionary spells it for (NSQK).
         */
        fun spokenToName(): Map<String, String> {
            val out = HashMap<String, String>()
            for (entry in entries.values) {
                listOfNotNull(entry.es, entry.en).forEach { said ->
                    val key = fold(said)
                    if (key.length >= 4) out[key] = entry.name
                }
            }
            return out
        }

        /** Every name in the dictionary, as it was written there. */
        val names: List<String> = entries.values.map { it.name }

        companion object {
            val EMPTY = Lexicon(emptyMap())

            /** `name<TAB>es<TAB>en` lines; `#` starts a comment, and a blank cell leaves the name as it is. */
            fun parse(text: String): Lexicon {
                val out = LinkedHashMap<String, Entry>()
                text.lineSequence().forEach { raw ->
                    val line = raw.trimEnd()
                    if (line.isBlank() || line.startsWith("#")) return@forEach
                    val cells = line.split('\t')
                    val name = cells.firstOrNull()?.trim().orEmpty()
                    if (name.isEmpty()) return@forEach
                    val es = cells.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    val en = cells.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                    if (es == null && en == null) return@forEach
                    out[fold(name)] = Entry(name, es, en)
                }
                return Lexicon(out)
            }
        }
    }

    /**
     * [line] ready for the voice: every artist in it that the dictionary or the rules know how to say is replaced by
     * its respelling. [names] are the artists the line may be talking about (the session's), which the rules apply to;
     * the dictionary is looked through the whole line, whoever it names. [alias] gives a readable form of a name that
     * is not in letters the voice can read, when one is known.
     */
    fun prepare(
        line: String,
        language: String,
        names: Collection<String>,
        lexicon: Lexicon,
        alias: (String) -> String? = { null },
    ): String {
        if (line.isBlank()) return line
        // Longest first, so "Bad Bunny" is taken whole before "Bunny" could be.
        val candidates = (names.asSequence() + lexicon.names.asSequence().filter { containsName(line, it) })
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { fold(it) }
            .sortedByDescending { it.length }
            .toList()
        var result = line
        for (name in candidates) {
            val spoken = lexicon.lookup(name, language)
                ?: alias(name)?.takeIf { it.isNotBlank() }
                ?: respell(name, language)
                ?: continue
            if (spoken == name) continue
            result = replaceName(result, name, spoken)
        }
        return result
    }

    /**
     * How the rules would say [name] when the dictionary does not know it, or null when it reads fine as it is:
     * a name with no vowel (MGMT, BTS) is spelled out, letter by letter, as the voice's language names the letters;
     * the symbols some names are written with ($, !) stand for the letters they replace.
     */
    fun respell(name: String, language: String): String? {
        val symbols = name.replace('$', 's').replace(Regex("(?<=\\p{L})!(?=\\p{L})"), "i")
        if (symbols != name && symbols.any { it.isLetter() }) return symbols
        val word = name.trim()
        // One word, letters only, and no vowel in it: a voice has nothing to pronounce and would mumble or skip it.
        if (word.length in 2..6 && word.all { it.isLetter() } && word.none { isVowel(it) }) {
            val letters = if (language == "es") LETTERS_ES else LETTERS_EN
            return word.map { letters[it.lowercaseChar()] ?: it.toString() }.joinToString(" ")
        }
        return null
    }

    /** Whether [text] has a character a Spanish or English voice cannot read: a name to look up a readable form for. */
    fun needsAlias(name: String): Boolean = name.any { c ->
        c.isLetter() && Character.UnicodeScript.of(c.code).let { it != Character.UnicodeScript.LATIN && it != Character.UnicodeScript.COMMON }
    }

    /** [name] without case, accents or punctuation, for matching: "AC/DC" and "ac dc" are one key. */
    fun fold(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    private fun isVowel(c: Char): Boolean = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().lowercaseChar() in "aeiouy"

    /** Whether [line] mentions [name] as a whole word. */
    fun mentions(line: String, name: String): Boolean = nameRegex(name).containsMatchIn(line)

    private fun containsName(line: String, name: String): Boolean = mentions(line, name)

    private fun replaceName(line: String, name: String, spoken: String): String =
        nameRegex(name).replace(line) { spoken }

    /** The name as a whole word, any case, however its punctuation is spaced. */
    private fun nameRegex(name: String): Regex {
        val parts = name.trim().split(Regex("\\s+")).map { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])" + parts.joinToString("\\s+") + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    private val LETTERS_ES = mapOf(
        'a' to "a", 'b' to "be", 'c' to "ce", 'd' to "de", 'e' to "e", 'f' to "efe", 'g' to "ge", 'h' to "hache",
        'i' to "i", 'j' to "jota", 'k' to "ka", 'l' to "ele", 'm' to "eme", 'n' to "ene", 'ñ' to "eñe", 'o' to "o",
        'p' to "pe", 'q' to "cu", 'r' to "erre", 's' to "ese", 't' to "te", 'u' to "u", 'v' to "uve",
        'w' to "doble ve", 'x' to "equis", 'y' to "i griega", 'z' to "zeta",
    )

    private val LETTERS_EN = mapOf(
        'a' to "ay", 'b' to "bee", 'c' to "see", 'd' to "dee", 'e' to "ee", 'f' to "eff", 'g' to "jee", 'h' to "aitch",
        'i' to "eye", 'j' to "jay", 'k' to "kay", 'l' to "ell", 'm' to "em", 'n' to "en", 'o' to "oh", 'p' to "pee",
        'q' to "cue", 'r' to "are", 's' to "ess", 't' to "tee", 'u' to "you", 'v' to "vee", 'w' to "double you",
        'x' to "ex", 'y' to "why", 'z' to "zee",
    )
}
