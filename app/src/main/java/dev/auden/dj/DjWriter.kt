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
    /** The hour, for naming the moment ("esta noche") in a set's reason. */
    private val hour: Int = 12,
    @Suppress("unused") private val weekend: Boolean = false,
    /** Now, to say how long ago a song was last played. */
    private val nowMs: Long = 0L,
    /** What the listener has played of an artist: this month, and ever. Null when unknown. */
    private val artistPlays: (String) -> Pair<Int, Int>? = { null },
) {
    private val rng = Random(seed)
    private val lastUsed = HashMap<String, ArrayDeque<Int>>()

    /** What the listener last asked for, if anything: a set that matches it says so. */
    var request: DjRequest? = null

    /** Said once, as the session starts: a greeting, then why the first set is what it is. */
    fun opening(@Suppress("UNUSED_PARAMETER") hour: Int, first: DjBlock, request: DjRequest?, relaxed: Boolean): String {
        this.request = request
        return join(
            pick(greetingKey(this.hour)),
            when {
                request != null && !request.understood && request.text.isNotBlank() -> pick("unrecognized")
                relaxed -> pick("relaxed")
                else -> null
            },
            introduce(first),
        )
    }

    /** Said as a new set begins: why these songs, and the one it starts with. */
    fun blockIntro(block: DjBlock, @Suppress("UNUSED_PARAMETER") previous: DjBlock?): String = introduce(block).orEmpty()

    /**
     * A remark on a song, only when there is something about it worth saying: how often the listener has
     * played it, which playlist it is from, its album and year. A song with nothing to say gets nothing:
     * the title and artist alone are not a remark.
     */
    fun trackNote(track: DjTrack): String? = listOfNotNull(
        track.plays30.takeIf { it >= 3 }?.let { pick("note_repeat", "title" to track.title, "n" to it.toString()) },
        track.playlists.firstOrNull()?.let { pick("note_playlist", "title" to track.title, "playlist" to it) },
        if (track.year != null && !track.album.isNullOrBlank()) {
            pick("note_album_year", "title" to track.title, "album" to track.album, "year" to track.year.toString())
        } else null,
    ).let { options -> if (options.isEmpty()) null else options[rng.nextInt(options.size)] }

    fun signOff(): String = pick("signoff") ?: ""

    // ── Introducing a set: the reason first, the first song second ─────────

    /**
     * Why the set is what it is, in a sentence about the listener, and then the song it opens on. The
     * reason is a fact the planner and the listening history both support: what they asked for, how much
     * they have been playing an artist, how long since they heard a song, which playlist, what time of
     * day it is. The opening song only closes the sentence; it is never the announcement.
     */
    private fun introduce(block: DjBlock): String? {
        val first = block.tracks.firstOrNull() ?: return null
        val theme = block.theme
        val opener = openerText(first, theme)
        if (matchesRequest(theme)) {
            val what = what(theme)
            return pick("intro_request_match", "what" to what, "what_cap" to what?.cap(), "opener" to opener)
        }
        return when (theme) {
            is DjTheme.ByArtist -> {
                val (month, ever) = artistPlays(theme.artist) ?: (0 to 0)
                val key = when {
                    month >= RECENT_ARTIST_PLAYS -> "intro_artist_recent"
                    ever >= REGULAR_ARTIST_PLAYS -> "intro_artist_always"
                    else -> "intro_artist_saved"
                }
                pick(key, "artist" to theme.artist, "opener" to opener)
            }
            is DjTheme.ByMood -> {
                val moment = pick(momentKey())
                pick(
                    "intro_mood_${theme.mood.name.lowercase(Locale.ROOT)}",
                    "moment" to moment, "moment_cap" to moment?.cap(), "opener" to opener,
                )
            }
            DjTheme.Favorites -> pick("intro_favorites", "opener" to opener)
            DjTheme.Rediscover -> {
                val ago = agoText(first.lastPlayedAt)
                pick("intro_rediscover", "ago" to ago, "opener" to opener) ?: pick("intro_rediscover_plain", "opener" to opener)
            }
            DjTheme.Fresh -> pick("intro_fresh", "opener" to opener)
            DjTheme.Discover -> {
                val seed = block.tracks.firstNotNullOfOrNull { it.because }
                pick("intro_discover", "seed" to seed, "opener" to opener) ?: pick("intro_discover_plain", "opener" to opener)
            }
            is DjTheme.ByPlaylist -> pick("intro_playlist", "playlist" to theme.name, "opener" to opener)
            is DjTheme.ByDecade, is DjTheme.ByGenre, is DjTheme.ByAlbum -> pick("intro_theme", "what" to what(theme), "opener" to opener)
        }
    }

    /** "Song, by Artist"; just the title when the set is already that artist's. */
    private fun openerText(track: DjTrack, theme: DjTheme): String {
        if (theme is DjTheme.ByArtist) return track.title
        return pick("by_artist", "title" to track.title, "artist" to track.artist) ?: track.title
    }

    /** The set as a thing the listener could have asked for: "music by X", "something calm"… */
    private fun what(theme: DjTheme): String? = when (theme) {
        is DjTheme.ByArtist -> pick("what_artist", "artist" to theme.artist)
        is DjTheme.ByDecade -> pick("what_decade", "decade" to decadeLabel(theme.decade))
        is DjTheme.ByGenre -> pick("what_genre", "genre" to theme.genre)
        is DjTheme.ByAlbum -> pick("what_album", "album" to theme.album, "artist" to theme.artist)
        is DjTheme.ByMood -> pick("mood_word_${theme.mood.name.lowercase(Locale.ROOT)}")
        DjTheme.Favorites -> pick("what_favorites")
        DjTheme.Fresh -> pick("what_fresh")
        DjTheme.Rediscover -> pick("what_rediscover")
        else -> null
    }

    /** Whether [theme] is what the listener asked for: the one case where "you asked for" is true. */
    private fun matchesRequest(theme: DjTheme): Boolean {
        val r = request?.takeIf { it.understood } ?: return false
        return when (theme) {
            is DjTheme.ByArtist -> r.artist.equals(theme.artist, ignoreCase = true)
            is DjTheme.ByDecade -> r.decade == theme.decade
            is DjTheme.ByGenre -> r.genre == theme.genre
            is DjTheme.ByMood -> r.mood == theme.mood
            DjTheme.Favorites -> r.favorites
            DjTheme.Fresh -> r.fresh
            DjTheme.Rediscover -> r.rediscover
            else -> false
        }
    }

    /** How long ago, in the unit a person would use; null when the song has no history. */
    private fun agoText(lastPlayedAt: Long): String? {
        if (lastPlayedAt <= 0L || nowMs <= 0L) return null
        val months = ((nowMs - lastPlayedAt) / (30L * 24 * 60 * 60 * 1000)).toInt()
        return when {
            months >= 12 -> pick("ago_year")
            months >= 2 -> pick("ago_months", "n" to months.toString())
            else -> null
        }
    }

    private fun momentKey(): String = when (hour) {
        in 5..11 -> "moment_morning"
        in 12..18 -> "moment_afternoon"
        in 19..22 -> "moment_evening"
        else -> "moment_night"
    }

    private fun String.cap(): String = replaceFirstChar { it.uppercase() }

    /** The theme as a noun phrase, for places that need to name it. */
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

    private companion object {
        /** Plays this month that make an artist "lately"; plays ever that make them a regular. */
        const val RECENT_ARTIST_PLAYS = 5
        const val REGULAR_ARTIST_PLAYS = 20
    }
}
