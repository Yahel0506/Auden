package dev.auden.dj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a language model made of a spoken or typed ask, as data the planner can obey: the artists and songs named,
 * what to leave out, the decades, genres and mood, and the searches that would find what the library lacks. The
 * model never picks the songs; it only says what was asked, and [toRequest] holds every name it gave against what
 * the library really has before anything is played.
 */
@Serializable
data class DjPlan(
    val artists: List<String> = emptyList(),
    @SerialName("exclude_artists") val excludeArtists: List<String> = emptyList(),
    val songs: List<PlannedSong> = emptyList(),
    @SerialName("similar_to") val similarTo: List<String> = emptyList(),
    val decades: List<Int> = emptyList(),
    val genres: List<String> = emptyList(),
    @SerialName("exclude_genres") val excludeGenres: List<String> = emptyList(),
    val mood: String? = null,
    val playlist: String? = null,
    val album: String? = null,
    val favorites: Boolean = false,
    val fresh: Boolean = false,
    val rediscover: Boolean = false,
    @SerialName("local_only") val localOnly: Boolean = false,
    @SerialName("search_queries") val searchQueries: List<String> = emptyList(),
    /** Which parts of the ask must not be given up when the library falls short: artists, decades, genres, mood. */
    val strict: List<String> = emptyList(),
    /** A short name for the session, in the listener's language: what the DJ calls it out loud, never the ask itself. */
    val title: String? = null,
) {
    @Serializable
    data class PlannedSong(val title: String, val artist: String? = null)

    /**
     * The ask as the planner reads it. Names are matched against [pool] (the way a typed name is: as written, as
     * said, or a letter off), the ones the library does not hold becoming searches instead. [fallback] is what the
     * keyword reader made of the same ask: it fills in whatever this plan left empty, and stands in for all of it
     * when the plan said nothing.
     */
    fun toRequest(text: String, pool: List<DjTrack>, aliases: Map<String, String> = emptyMap(), fallback: DjRequest? = null): DjRequest {
        val libraryArtists = ArrayList<String>()
        val missing = ArrayList<String>()
        for (name in artists) {
            val held = DjRequestParser.artistIn(DjRequestParser.fold(name), pool, aliases)
            if (held != null) libraryArtists += held else missing += name
        }
        val excluded = excludeArtists.map { name ->
            DjRequestParser.artistIn(DjRequestParser.fold(name), pool, aliases) ?: name
        }
        val songIds = ArrayList<String>()
        val songSearches = ArrayList<String>()
        for (s in songs) {
            val hit = findSong(s, pool)
            if (hit != null) songIds += hit.id
            else songSearches += listOfNotNull(s.title, s.artist).joinToString(" ")
        }
        val decadeList = decades.map { it / 10 * 10 }.filter { it in 1900..2090 }.distinct().sorted()
        val genre = genres.firstNotNullOfOrNull { g ->
            val f = DjRequestParser.fold(g)
            pool.asSequence().mapNotNull { it.genreKey }.distinct()
                .filter { it.length >= 3 }
                .firstOrNull { k -> DjRequestParser.fold(k).let { it == f || it.contains(f) || f.contains(it) } }
        }
        // A genre the library has none of ("música clásica" for a rock library) is not dropped: it is searched for.
        val unmetGenres = if (genre == null) genres.filter { it.isNotBlank() } else emptyList()
        val moodValue = mood?.let { m -> DjMood.entries.firstOrNull { it.name.equals(m.trim(), ignoreCase = true) } }
        val similarSeed = similarTo.firstOrNull()?.let { DjRequestParser.artistIn(DjRequestParser.fold(it), pool, aliases) }
        val asNew = similarTo.isNotEmpty()
        val searches = buildList {
            searchQueries.filter { it.isNotBlank() }.forEach { add(it.trim()) }
            // An artist the library lacks is searched by its name, as an ask that is only a name is.
            missing.forEach { add(it) }
            if (searchQueries.none { it.isNotBlank() }) unmetGenres.forEach { add("$it music") }
            similarTo.filter { similarSeed == null }.forEach { add("$it similar artists") }
        }.distinct().take(MAX_QUERIES)

        val base = fallback ?: DjRequest(text = text.trim())
        val planned = base.copy(
            text = text.trim(),
            artist = null,
            artists = libraryArtists.distinct(),
            excludeArtists = excluded.distinct(),
            excludeGenres = excludeGenres.map { DjRequestParser.fold(it) }.filter { it.isNotBlank() },
            songIds = songIds.distinct(),
            queries = searches,
            songQueries = songSearches.distinct().take(MAX_QUERIES),
            searchNames = missing.distinct(),
            decade = decadeList.firstOrNull() ?: base.decade,
            decades = decadeList.ifEmpty { base.decades },
            genre = genre ?: base.genre.takeIf { genres.isEmpty() },
            mood = moodValue ?: base.mood.takeIf { mood == null },
            album = album?.takeIf { it.isNotBlank() } ?: base.album,
            playlist = playlist?.takeIf { it.isNotBlank() } ?: base.playlist,
            favorites = favorites || base.favorites,
            fresh = fresh || base.fresh,
            rediscover = rediscover || base.rediscover,
            localOnly = localOnly || base.localOnly,
            discover = asNew || base.discover,
            discoverSeed = similarSeed ?: base.discoverSeed,
            strict = strict.map { it.trim().lowercase() }.toSet(),
            title = title?.trim()?.trim('"', '.', '!')?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE },
            catalogueOnly = unmetGenres.isNotEmpty() || (artists.isNotEmpty() && libraryArtists.isEmpty()),
        )
        return planned
    }

    companion object {
        private const val MAX_QUERIES = 5
        private const val MAX_TITLE = 40
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        /** The plan in [body], or null when it is not one. The service answers with the plan alone; models sometimes fence it. */
        fun parse(body: String): DjPlan? {
            val trimmed = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return runCatching { json.decodeFromString(serializer(), trimmed) }.getOrNull()
        }

        /** A song of the library that [wanted] names: its title, and its artist when one was given. */
        internal fun findSong(wanted: PlannedSong, pool: List<DjTrack>): DjTrack? {
            val title = DjRequestParser.fold(wanted.title).filter { it.isLetterOrDigit() }
            if (title.length < 2) return null
            val artist = wanted.artist?.let { DjRequestParser.fold(it).filter { c -> c.isLetterOrDigit() } }?.takeIf { it.isNotEmpty() }
            val sameTitle = pool.filter { t ->
                val k = DjRequestParser.fold(t.title).filter { it.isLetterOrDigit() }
                k == title || (title.length >= 5 && DjRequestParser.distance(k, title, 1) <= 1)
            }
            if (artist == null) return sameTitle.maxByOrNull { it.plays }
            return sameTitle.filter { t ->
                val a = DjRequestParser.fold(t.artist).filter { it.isLetterOrDigit() }
                a.contains(artist) || artist.contains(DjRequestParser.fold(t.leadArtist).filter { it.isLetterOrDigit() })
            }.maxByOrNull { it.plays }
        }
    }
}
