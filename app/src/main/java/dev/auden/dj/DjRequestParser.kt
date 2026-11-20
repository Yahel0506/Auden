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

    /**
     * [aliases] are other ways an artist is said or spelled — folded, without spaces — to the name it stands for:
     * how the DJ's own voice says "NSQK" ("nescuic") is how someone who speaks to it will say it too.
     */
    fun parse(text: String, pool: List<DjTrack>, aliases: Map<String, String> = emptyMap()): DjRequest {
        val q = fold(text)
        if (q.isBlank()) return DjRequest(text = text)

        val artist = artistIn(q, pool, aliases)
        // "como X", "something like X": new music in X's line, not X's own songs.
        val like = artist != null && LIKE_WORDS.any { containsWord(q, it) }
        val decades = decadesIn(q)

        val genre = pool.asSequence().mapNotNull { it.genreKey }.distinct()
            .filter { it.length >= 3 }
            .filter { val f = fold(it); q.contains(f) && containsWord(q, f) }
            .maxByOrNull { it.length }

        val mood = when {
            hasAny(q, "concentr", "estudi", "trabaj", "focus", "study", "work", "instrumental", "sin letra", "no lyrics", "without lyrics") -> DjMood.FOCUS
            hasAny(q, "dormir", "sleep", "nana", "lullaby", "meditar", "meditation") -> DjMood.SLEEP
            hasAny(q, "relaj", "tranquil", "calm", "chill", "suave", "soft", "relax") -> DjMood.CHILL
            hasAny(q, "entren", "gym", "workout", "correr", "running", "ejercicio") -> DjMood.WORKOUT
            hasAny(q, "fiesta", "party", "bailar", "dance", "antro", "perreo", "club") -> DjMood.PARTY
            hasAny(q, "energi", "energ", "animad", "upbeat") -> DjMood.ENERGY
            hasAny(q, "romant", "amor", "love", "cita", "date night", "cena") -> DjMood.ROMANTIC
            hasAny(q, "trist", "sad", "melanc", "llorar", "cry", "heartbreak", "desamor") -> DjMood.MELANCHOLY
            hasAny(q, "feliz", "happy", "alegr", "good vibes", "verano", "summer") -> DjMood.HAPPY
            hasAny(q, "carretera", "road trip", "viaje", "manejar", "driving") -> DjMood.ROAD_TRIP
            hasAny(q, "nostalg", "retro", "oldies", "recuerdos", "vintage") -> DjMood.NOSTALGIC
            hasAny(q, "latin", "reggaeton", "salsa", "bachata", "cumbia") -> DjMood.LATIN
            hasAny(q, "noche", "night", "madrugada", "midnight") -> DjMood.NIGHT
            hasAny(q, "cafe", "café", "coffee", "brunch", "desayuno") -> DjMood.COFFEE
            hasAny(q, "lluvia", "rain", "nublado", "rainy") -> DjMood.RAINY
            hasAny(q, "acustic", "acoustic", "unplugged") -> DjMood.ACOUSTIC
            else -> null
        }

        return DjRequest(
            text = text.trim(),
            artist = artist.takeUnless { like },
            decade = decades.firstOrNull(),
            decades = decades,
            discover = like,
            discoverSeed = artist.takeIf { like },
            genre = genre,
            mood = mood,
            favorites = hasAny(q, "favorit", "mejores", "mas escuchad", "best of", "my best", "most played", "top "),
            fresh = hasAny(q, "nuev", "recient", "recien", "new ", "recent", "latest", "fresh"),
            rediscover = hasAny(q, "olvidad", "clasicos", "clasicas", "antigu", "forgotten", "throwback", "nostalg", "rediscover", "redescubr"),
            localOnly = hasAny(q, "sin conexion", "offline", "descargad", "downloaded", "sin internet", "no internet"),
        )
    }

    /**
     * Whether [text] names a genre of its own ("rock en español", "some jazz"): the one case where an ask is
     * not bent towards the listener's own genres. Mood and intention words are not genres.
     */
    fun namesGenre(text: String): Boolean {
        val q = fold(text)
        return GENRE_WORDS.any { containsWord(q, it) } || GENRE_STEMS.any { q.contains(it) }
    }

    private val GENRE_WORDS = listOf(
        "rock", "pop", "rap", "hip hop", "hiphop", "hip-hop", "trap", "reggaeton", "regueton", "reggae", "ska", "jazz", "blues",
        "metal", "punk", "indie", "techno", "house", "edm", "cumbia", "salsa", "bachata", "merengue", "banda", "corridos", "corrido",
        "norteno", "norteña", "mariachi", "ranchera", "rancheras", "folk", "country", "soul", "funk", "disco", "grunge", "emo",
        "lofi", "lo-fi", "bossa nova", "flamenco", "tango", "kpop", "k-pop", "jpop", "latin", "latino", "balada", "baladas",
        "gospel", "opera", "classical", "clasica", "r&b", "rnb", "dubstep", "drill", "alternativo", "alternative", "electronica",
        "electronic", "electro", "synthpop", "shoegaze", "hardcore", "trova", "boleros", "bolero", "vallenato", "sertanejo",
    )
    private val GENRE_STEMS = listOf("hip hop", "hip-hop", "regional mexican", "musica regional")

    /** The first decade the ask names; see [decadesIn]. */
    internal fun decadeIn(q: String): Int? = decadesIn(q).firstOrNull()

    /**
     * Every decade the ask names, oldest first: "los 80", "90s", "años 70", "1985", "ochentas", "the eighties", and
     * several at once — "los 80 y 90", "70s and 2000s", "de los 70 a los 90" (a range takes the ones between) —
     * or a span of years: "entre 1995 y 2005".
     */
    internal fun decadesIn(q: String): List<Int> {
        val found = java.util.TreeSet<Int>()
        var s = q

        // A span of years, or a single year.
        Regex("""\b((?:19|20)\d\d)\s*(?:a|al|hasta|to|through|-|y|and)\s*((?:19|20)\d\d)\b""").findAll(s).forEach { m ->
            val from = m.groupValues[1].toInt() / 10 * 10
            val to = m.groupValues[2].toInt() / 10 * 10
            if (from <= to) for (d in from..to step 10) found += d
        }
        Regex("""\b((?:19|20)\d)\d\b""").findAll(s).forEach { found += it.groupValues[1].toInt() * 10 }
        s = Regex("""\b(?:19|20)\d\d\b""").replace(s, " ")
        // "1990s", "2000s": the long form of the same decade, read as "90s", "00s" with the rest.
        s = Regex("""\b(?:19|20)(\d)0'?s\b""").replace(s) { " ${it.groupValues[1]}0s " }

        // Spoken decades ("ochentas", "the eighties", "dos mil") become the numbers they are, to be read with the rest.
        for ((stem, d) in SPOKEN_STEMS) {
            s = Regex("""(?:(?:los|las|anos|de|the|decada|years)\s+)?\b$stem\w*\b""").replace(s) { m ->
                val hasContext = m.value.contains(' ')
                val plural = m.value.endsWith("s") || m.value.endsWith("as") || m.value.contains("eros")
                if (hasContext || plural) " ${d % 100}s " else m.value
            }
        }
        for ((word, d) in SPOKEN_ENGLISH) s = s.replace(Regex("""\b$word\b"""), " ${d % 100}s ")
        if (Regex("""\b(?:dos ?miles|dosmiles|two thousands|noughties|aughts)\b""").containsMatchIn(s)) found += 2000

        // The numbers: "80", "90s", "'70s". One counts only with a word like "los" or "años" before it or an "s" after;
        // the ones beside it ("los 80 y 90") then count with it. "10 canciones" is only a number.
        val numbers = Regex("""(?<![\d.])([0-9])0('?s)?(?![\d])""").findAll(s).toList()
        val context = Regex("""(?:anos|los|las|de|the|decada|decade|years|musica|music|of|from|desde|entre|between)\s*$""")
        val valid = BooleanArray(numbers.size)
        for ((i, m) in numbers.withIndex()) {
            valid[i] = m.groupValues[2].isNotEmpty() || context.containsMatchIn(s.substring(0, m.range.first))
        }
        val link = Regex("""^\s*(?:(y|e|and|o|or|,|/|&)|(a|al|to|hasta|through|-|–))\s*(?:los|las|the|de)?\s*$""")
        // A number beside a valid one is valid too, whichever way the validity runs.
        for (pass in 0 until 2) {
            for (i in 1 until numbers.size) {
                val between = s.substring(numbers[i - 1].range.last + 1, numbers[i].range.first)
                if (link.matches(between)) {
                    if (valid[i - 1]) valid[i] = true
                    if (valid[i]) valid[i - 1] = true
                }
            }
        }
        fun decadeOf(m: MatchResult): Int? {
            val d = m.groupValues[1].toInt()
            return when (d) { in 5..9 -> 1900 + d * 10; in 0..2 -> 2000 + d * 10; else -> null }
        }
        for ((i, m) in numbers.withIndex()) {
            if (!valid[i]) continue
            val d = decadeOf(m) ?: continue
            found += d
            if (i > 0 && valid[i - 1]) {
                val between = s.substring(numbers[i - 1].range.last + 1, m.range.first)
                val range = link.matchEntire(between)?.groupValues?.get(2)?.isNotEmpty() == true
                val from = decadeOf(numbers[i - 1])
                if (range && from != null && from < d) for (x in from..d step 10) found += x
            }
        }
        return found.toList()
    }

    /**
     * The name an ask is only a name of — "SZA", "pon a SZA", "música de Taylor Swift" — or null when it says more than
     * that. Such an ask is for an artist the library does not hold (or the parser could not place): it is searched for
     * as the name, not bent towards the listener's own taste, which turned a request for one artist into a mix.
     */
    fun artistNameIn(text: String): String? = artistNamesIn(text).singleOrNull()

    /**
     * Every name an ask is only made of: "SZA y Taylor Swift" is two, "Taylor Swift" one. Empty when the ask says more
     * than names. A name with a joiner in it ("Simon & Garfunkel") comes back as the parts too: the caller also
     * searches the ask whole, which finds the one artist.
     */
    fun artistNamesIn(text: String): List<String> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size && fold(tokens[i]) in ASK_FILLER) i++
        val rest = tokens.drop(i).joinToString(" ")
        if (rest.isBlank()) return emptyList()
        val parts = rest.split(Regex("\\s*(?:,|&|\\+|\\s(?:y|and|e|con|with|feat\\.?|ft\\.?)\\s)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 4) return emptyList()
        for (part in parts) {
            val words = part.split(Regex("\\s+"))
            if (words.size > 4 || words.any { fold(it) in INTENT_WORDS }) return emptyList()
        }
        return parts
    }

    private val ASK_FILLER = setOf(
        "pon", "ponme", "pone", "poner", "dame", "reproduce", "toca", "tocame", "quiero", "escuchar", "oir", "play", "put", "on",
        "give", "me", "i", "want", "to", "hear", "listen", "can", "you", "algo", "something", "some", "musica", "music",
        "canciones", "cancion", "songs", "song", "temas", "de", "del", "by", "from", "of", "a", "la", "el", "lo", "mejor", "best",
        "mas", "more", "por", "favor", "please",
    )
    private val INTENT_WORDS = setOf("sin", "para", "with", "without", "for", "and", "y", "que", "con", "cuando", "like", "como")

    // ── The artist ─────────────────────────────────────────────────────────

    /**
     * The library's artist the ask names: as written, as it is said ([aliases]), or close enough to either to be a
     * misspelling or a mishearing ("kendrik", "imagin dragons"). The longest exact one wins; a near one is taken only
     * when nothing is exact, and only for names long enough that one wrong letter is not another name.
     */
    internal fun artistIn(q: String, pool: List<DjTrack>, aliases: Map<String, String> = emptyMap()): String? {
        val artists = pool.asSequence().map { it.leadArtist }.distinct().filter { it.length >= 2 }.toList()
        // A plain substring test first: the word-boundary check builds a regular expression, and a library
        // has thousands of artists, nearly all of which are not in the ask.
        artists.filter { val f = fold(it); q.contains(f) && containsWord(q, f) }.maxByOrNull { it.length }?.let { return it }

        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val windows = ArrayList<String>()
        for (start in tokens.indices) {
            var joined = ""
            for (len in 1..4) {
                if (start + len > tokens.size) break
                joined += tokens[start + len - 1]
                // A window of only filler ("de la", "the") names nobody.
                if (tokens.subList(start, start + len).all { it in FILLER }) continue
                if (joined.length >= 4) windows += joined
            }
        }
        if (windows.isEmpty()) return null

        // How it is said: an alias that is in the ask names the artist it stands for, if the library has it.
        val byKey = artists.groupBy { key(it) }
        aliases.entries.sortedByDescending { it.key.length }.firstOrNull { (alias, _) ->
            alias.length >= 4 && windows.any { it == alias || (alias.length >= 6 && distance(it, alias, 1) <= 1) }
        }?.let { (_, name) -> byKey[key(name)]?.firstOrNull()?.let { return it } }

        // Close to a name: the nearest artist, within a letter or two of what was said (more for longer names).
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (artist in artists) {
            val k = key(artist)
            if (k.length < 5) continue
            val allowed = if (k.length >= 9) 2 else 1
            for (w in windows) {
                if (kotlin.math.abs(w.length - k.length) > allowed) continue
                val dist = distance(w, k, allowed)
                if (dist <= allowed && (dist < bestDistance || dist == bestDistance && artist.length > (best?.length ?: 0))) {
                    best = artist
                    bestDistance = dist
                }
            }
        }
        return best
    }

    /** An artist's name as letters and digits only, folded: what a spoken or typed version is compared with. */
    private fun key(name: String): String = fold(name).filter { it.isLetterOrDigit() }

    /** The edit distance between [a] and [b], or more than [limit] as soon as it is known to be. */
    internal fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            var rowMin = row[0]
            for (j in 1..b.length) {
                row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, row[j])
            }
            if (rowMin > limit) return limit + 1
            previous = row
        }
        return previous[b.length]
    }

    private val LIKE_WORDS = listOf("como", "like", "similar", "parecid", "linea de", "estilo de", "style of", "sounds like", "suena a")
    private val FILLER = setOf("de", "la", "el", "los", "las", "un", "una", "the", "a", "an", "of", "and", "y", "o", "or", "para", "con", "en", "algo", "dame", "pon", "ponme", "quiero", "musica", "cancion", "canciones", "some", "play", "me", "music", "songs", "del", "al")
    private val SPOKEN_STEMS = listOf("cincuent" to 1950, "sesent" to 1960, "setent" to 1970, "ochent" to 1980, "novent" to 1990)
    private val SPOKEN_ENGLISH = listOf("fifties" to 1950, "sixties" to 1960, "seventies" to 1970, "eighties" to 1980, "nineties" to 1990)

    private fun hasAny(q: String, vararg stems: String) = stems.any { q.contains(it) }

    private fun containsWord(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && Regex("""(^|[^a-z0-9])${Regex.escape(needle)}($|[^a-z0-9])""").containsMatchIn(haystack)

    /** Lowercase without accents, so "Clásica" and "clasica" are one word. */
    internal fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
