package dev.auden.dj

import java.util.Locale
import java.util.Random

/** Where the DJ's wording comes from: lists of templates by name, in the listener's language. */
interface DjPhrases {
    /** The templates for [key], or empty if there are none in this language. */
    fun templates(key: String): List<String>
}

/**
 * Writes what the DJ says.
 *
 * Every line is built from facts the planner has just established — the artist, the decade, how
 * many songs, the title of the opener — slotted into a template, so a line can only say something
 * true about the block it introduces. A template whose slot has no fact is skipped rather than
 * filled with a guess. Variety comes from many templates per situation and from remembering the
 * last few used, so the same sentence does not come round twice in an evening.
 *
 * The DJ keeps it short, as a person on the radio does: one or two sentences a block.
 */
class DjWriter(
    private val phrases: DjPhrases,
    private val language: String,
    seed: Long,
    /** The hour and whether it is a weekend, for the moment the DJ can name. */
    private val hour: Int = 12,
    private val weekend: Boolean = false,
    /** The artist the listener has played most this month, if one clearly stands out. */
    private val topArtist: String? = null,
) {
    private val rng = Random(seed)
    private val lastUsed = HashMap<String, ArrayDeque<Int>>()

    /** Said once, as the session starts: the time of day, then the first block. */
    fun opening(hour: Int, first: DjBlock, request: DjRequest?, relaxed: Boolean): String = join(
        pick(greetingKey(hour)),
        when {
            request != null && !request.understood && request.text.isNotBlank() -> pick("unrecognized")
            relaxed -> pick("relaxed")
            request != null && request.understood -> pick("request_ack", "request" to request.text)
            else -> null
        },
        introduce(first, previous = null),
        // A surprise can say why it chose this: the moment, or what the listener has been playing.
        if (request == null) motive(always = true) else null,
    )

    /** Said as a new block starts, joining the one before to it. */
    fun blockIntro(block: DjBlock, previous: DjBlock?): String {
        // Decided before picking: a connector chosen and then dropped would still count as used.
        val connector = if (previous != null && rng.nextInt(3) != 0) pick("connector") else null
        return join(connector, introduce(block, previous), motive(always = false).takeIf { rng.nextInt(3) == 0 })
    }

    /** Why this moment suits the music: named from the clock, or from the artist the listener keeps coming back to. */
    private fun motive(always: Boolean): String? {
        val byArtist = topArtist != null && rng.nextBoolean()
        return if (byArtist) {
            pick("motive_top", "artist" to topArtist)
        } else {
            val moment = pick(momentKey()) ?: return null
            pick("motive_moment", "moment" to moment)
        }?.takeIf { always || it.isNotBlank() }
    }

    private fun momentKey(): String {
        val base = when (hour) {
            in 5..11 -> "moment_morning"
            in 12..18 -> "moment_afternoon"
            in 19..22 -> "moment_evening"
            else -> "moment_night"
        }
        return if (weekend) "moment_weekend" else base
    }

    /** A short remark on a song in the middle of a block, when there is something true to say about it. */
    fun trackNote(track: DjTrack): String? = listOfNotNull(
        pick("note_year", "title" to track.title, "artist" to track.artist, "year" to track.year?.toString()),
        pick("note_album", "title" to track.title, "album" to track.album, "artist" to track.artist),
        pick("note_plain", "title" to track.title, "artist" to track.artist),
    ).let { options -> if (options.isEmpty()) null else options[rng.nextInt(options.size)] }

    fun signOff(): String = pick("signoff") ?: ""

    // ── Introducing a block ─────────────────────────────────────────────────

    private fun introduce(block: DjBlock, previous: DjBlock?): String? {
        val opener = block.tracks.firstOrNull()
        val count = spell(block.tracks.size)
        val theme = block.theme
        val line = when (theme) {
            is DjTheme.ByArtist -> pick("intro_artist", "artist" to theme.artist, "count" to count, "title" to opener?.title)
            is DjTheme.ByDecade -> pick("intro_decade", "decade" to decadeLabel(theme.decade), "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            is DjTheme.ByGenre -> pick("intro_genre", "genre" to theme.genre, "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            is DjTheme.ByMood -> pick("intro_mood_${theme.mood.name.lowercase(Locale.ROOT)}", "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            is DjTheme.ByAlbum -> pick("intro_album", "album" to theme.album, "artist" to theme.artist, "count" to count)
            DjTheme.Favorites -> pick("intro_favorites", "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            DjTheme.Rediscover -> pick("intro_rediscover", "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            DjTheme.Fresh -> pick("intro_fresh", "count" to count, "title" to opener?.title, "artist" to opener?.artist)
            DjTheme.Discover -> pick("intro_discover", "count" to count, "title" to opener?.title, "artist" to opener?.artist)
        }
        return line
    }

    /** The theme as a noun phrase — what it would be called after "after …". */
    fun describe(theme: DjTheme): String? = when (theme) {
        is DjTheme.ByArtist -> theme.artist
        is DjTheme.ByDecade -> decadeLabel(theme.decade)
        is DjTheme.ByGenre -> theme.genre
        is DjTheme.ByAlbum -> theme.album
        else -> null
    }

    // ── Templates ───────────────────────────────────────────────────────────

    /**
     * One template of [key] with its slots filled, never one used in the last few picks, never one
     * with a slot that has no value. Null when nothing fits.
     */
    private fun pick(key: String, vararg slots: Pair<String, String?>): String? {
        val all = phrases.templates(key)
        if (all.isEmpty()) return null
        val values = slots.toMap()
        val usable = all.indices.filter { fill(all[it], values) != null }
        if (usable.isEmpty()) return null
        val recent = lastUsed.getOrPut(key) { ArrayDeque() }
        val fresh = usable.filter { it !in recent }.ifEmpty { usable }
        val index = fresh[rng.nextInt(fresh.size)]
        recent.addLast(index)
        while (recent.size > minOf(4, all.size - 1).coerceAtLeast(0)) recent.removeFirst()
        return fill(all[index], values)
    }

    /** [template] with {slots} replaced, or null if any slot named in it has no value. */
    internal fun fill(template: String, values: Map<String, String?>): String? {
        var missing = false
        val out = Regex("""\{(\w+)\}""").replace(template) { m ->
            val v = values[m.groupValues[1]]?.takeIf { it.isNotBlank() }
            if (v == null) { missing = true; "" } else v
        }
        return if (missing) null else out
    }

    private fun greetingKey(hour: Int) = when (hour) {
        in 5..11 -> "greeting_morning"
        in 12..18 -> "greeting_afternoon"
        in 19..22 -> "greeting_evening"
        else -> "greeting_night"
    }

    private fun join(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")

    // ── Words ───────────────────────────────────────────────────────────────

    /** A number the way a person says it in a sentence about a few songs. */
    private fun spell(n: Int): String {
        val words = when (language) {
            "es" -> listOf("cero", "una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez")
            "pt" -> listOf("zero", "uma", "duas", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez")
            "fr" -> listOf("zéro", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix")
            "de" -> listOf("null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn")
            "it" -> listOf("zero", "una", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci")
            else -> listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        }
        return words.getOrNull(n) ?: n.toString()
    }

    /** "los 90", "the 90s", "les années 90", ... */
    fun decadeLabel(decade: Int): String {
        val short = if (decade >= 2000) decade.toString() else (decade % 100).toString()
        val plural = if (decade >= 2000) "${decade}s" else "${short}s"
        return when (language) {
            "es" -> "los $short".let { if (decade >= 2000) "los ${decade}" else it }
            "pt" -> if (decade >= 2000) "os anos $decade" else "os anos $short"
            "fr" -> if (decade >= 2000) "les années $decade" else "les années $short"
            "de" -> if (decade >= 2000) "die ${decade}er" else "die ${short}er"
            "it" -> if (decade >= 2000) "gli anni $decade" else "gli anni $short"
            else -> "the $plural"
        }
    }
}
