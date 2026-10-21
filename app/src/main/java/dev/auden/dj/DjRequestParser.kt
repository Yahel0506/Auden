package dev.auden.dj

import java.text.Normalizer
import java.util.Locale

/**
 * Turns "algo tranquilo de los 90" or "some focus music" into filters the planner can obey.
 *
 * Deliberately not a language model: it reads the request against what is actually in the library
 * (its artists, its genres) and a small vocabulary of moods, decades and intentions in Spanish and
 * English. What it cannot map — "sin letra" beyond instrumental genres, "para correr a las seis" —
 * is not guessed at; the DJ says it did not understand and chooses itself. It never names a song.
 */
object DjRequestParser {

    fun parse(text: String, pool: List<DjTrack>): DjRequest {
        val q = fold(text)
        if (q.isBlank()) return DjRequest(text = text)

        val artist = pool.asSequence().map { it.leadArtist }.distinct()
            .filter { it.length >= 2 && containsWord(q, fold(it)) }
            .maxByOrNull { it.length }

        val genre = pool.asSequence().mapNotNull { it.genreKey }.distinct()
            .filter { it.length >= 3 && containsWord(q, fold(it)) }
            .maxByOrNull { it.length }

        val mood = when {
            hasAny(q, "concentr", "estudi", "trabaj", "focus", "study", "work", "instrumental", "sin letra", "no lyrics", "without lyrics") -> DjMood.FOCUS
            hasAny(q, "relaj", "tranquil", "calm", "chill", "dormir", "sleep", "suave", "soft", "relax") -> DjMood.CHILL
            hasAny(q, "energi", "energ", "entren", "gym", "workout", "correr", "running", "fiesta", "party", "bailar", "dance", "animad", "upbeat") -> DjMood.ENERGY
            hasAny(q, "acustic", "acoustic", "unplugged") -> DjMood.ACOUSTIC
            else -> null
        }

        return DjRequest(
            text = text.trim(),
            artist = artist,
            decade = decadeIn(q),
            genre = genre,
            mood = mood,
            favorites = hasAny(q, "favorit", "mejores", "mas escuchad", "best of", "my best", "most played", "top "),
            fresh = hasAny(q, "nuev", "recient", "recien", "new ", "recent", "latest", "fresh"),
            rediscover = hasAny(q, "olvidad", "clasic", "antigu", "forgotten", "throwback", "nostalg", "rediscover", "redescubr"),
            localOnly = hasAny(q, "sin conexion", "offline", "descargad", "downloaded", "sin internet", "no internet"),
        )
    }

    /** "90s", "los 80", "años 70", "1985", "noventa", "the eighties". */
    internal fun decadeIn(q: String): Int? {
        Regex("""\b(19|20)(\d)\d\b""").find(q)?.let { return it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt() * 10 }
        Regex("""(?:(anos|los|de|the|decada|decade|years)\s+)?\b(\d)0('?s)?\b""").findAll(q).forEach { m ->
            val d = m.groupValues[2].toInt()
            // Without an "s" or a word like "los" or "años" it is only a number: "10 canciones".
            if (m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty()) return@forEach
            // "90s" and "los 90" are the nineties; "00s" is the 2000s and "10s" the 2010s.
            if (d in 5..9) return 1900 + d * 10
            if (d in 0..2) return 2000 + d * 10
        }
        val words = mapOf(
            "cincuenta" to 1950, "sesenta" to 1960, "setenta" to 1970, "ochenta" to 1980, "noventa" to 1990,
            "fifties" to 1950, "sixties" to 1960, "seventies" to 1970, "eighties" to 1980, "nineties" to 1990,
        )
        return words.entries.firstOrNull { containsWord(q, it.key) }?.value
    }

    private fun hasAny(q: String, vararg stems: String) = stems.any { q.contains(it) }

    private fun containsWord(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && Regex("""(^|[^a-z0-9])${Regex.escape(needle)}($|[^a-z0-9])""").containsMatchIn(haystack)

    /** Lowercase without accents, so "Clásica" and "clasica" are one word. */
    internal fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
