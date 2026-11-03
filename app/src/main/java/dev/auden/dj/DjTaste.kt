package dev.auden.dj

import java.util.Locale

/**
 * How a typed ask is turned into searches that fit what the listener plays, and how the answers are mixed.
 *
 * "Música triste" alone finds the saddest songs of anything. The listener who plays rock and hip-hop wants
 * the sad ones of those, unless they named another genre, so the ask is searched once in each of their
 * genres. And an artist of theirs who has a song that fits is welcome whatever their genre, so it is
 * searched from each of their favourite artists too. Pure: the session does the searching.
 */
object DjTaste {

    /** What the listener plays: their favourite artists, and the genres those artists and files are filed under. */
    class Profile(val artists: List<String>, val genres: List<String>) {
        val isEmpty: Boolean get() = artists.isEmpty() && genres.isEmpty()
    }

    sealed interface Query {
        val text: String

        /** The ask on its own. */
        data class Plain(override val text: String) : Query

        /** The ask in one of the listener's genres. */
        data class InGenre(override val text: String, val genre: String) : Query

        /** The ask from one of the listener's artists: their songs count whatever the genre. */
        data class FromArtist(override val text: String, val artist: String) : Query
    }

    /** A song a search returned. */
    data class Hit(val id: String, val artist: String)

    /** The artists played most (this month counts double) and the genres they come in, from the songs themselves and from [genresOf]. */
    fun profile(
        tracks: Collection<DjTrack>,
        genresOf: (String) -> List<String>,
        maxArtists: Int = MAX_ARTISTS,
        maxGenres: Int = MAX_GENRES,
    ): Profile {
        val own = tracks.filter { !it.discovery && it.plays > 0 }
        val artists = own.groupBy { it.leadArtist }.entries
            .sortedByDescending { (_, songs) -> songs.sumOf { it.plays30 * 2 + it.plays } }
            .map { it.key }
            .take(maxArtists)
        val weight = HashMap<String, Int>()
        own.forEach { t -> t.genreKey?.let { weight.merge(it, t.plays, Int::plus) } }
        artists.forEachIndexed { i, artist ->
            genresOf(artist).take(2).forEach { g ->
                DjTrack.normalizeGenre(g)?.let { weight.merge(it, maxArtists - i, Int::plus) }
            }
        }
        val genres = weight.entries.sortedByDescending { it.value }.map { it.key }.filterNot { it in NOT_GENRES }.take(maxGenres)
        return Profile(artists, genres)
    }

    /**
     * The searches for [text]. One that names a genre of its own ([explicitGenre]) is searched as it is: the
     * listener said what they want. Otherwise in each of their genres, and from each of their artists.
     */
    fun queries(text: String, profile: Profile, explicitGenre: Boolean): List<Query> {
        if (explicitGenre || profile.isEmpty) return listOf(Query.Plain(text))
        return profile.genres.map { Query.InGenre("$text $it", it) } +
            profile.artists.take(ARTISTS_SEARCHED).map { Query.FromArtist("$text $it", it) } +
            Query.Plain(text)
    }

    /**
     * The songs for a set, in order. The genre searches take turns, one song each, so a rock and hip-hop
     * listener gets both; within each, the artists the listener already plays come first. The artists' own
     * songs that fit join the turns (a few: they are a welcome extra, not the set). The ask on its own only
     * fills what is left. No artist more than [perArtist] times.
     */
    fun merge(
        results: List<Pair<Query, List<Hit>>>,
        knownArtists: Set<String>,
        limit: Int,
        perArtist: Int = PER_ARTIST,
    ): List<String> {
        fun known(h: Hit) = fold(DjTrack.leadOf(h.artist)) in knownArtists
        fun knownFirst(hits: List<Hit>) = hits.sortedBy { if (known(it)) 0 else 1 }

        val genreLists = results.filter { it.first is Query.InGenre }.map { knownFirst(it.second) }
        val artistList = interleave(
            results.mapNotNull { (q, hits) ->
                (q as? Query.FromArtist)?.let { fa ->
                    // Only the artist's own songs, and the first couple the search ranks for the ask.
                    hits.filter { fold(it.artist).contains(fold(fa.artist)) }.take(ARTIST_HITS)
                }
            },
        ).take(ARTIST_EXTRAS)
        val plain = results.filter { it.first is Query.Plain }.flatMap { knownFirst(it.second) }

        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val counts = HashMap<String, Int>()
        fun take(h: Hit): Boolean {
            if (out.size >= limit || h.id in seen) return false
            val who = fold(DjTrack.leadOf(h.artist))
            if ((counts[who] ?: 0) >= perArtist) return false
            seen += h.id
            counts.merge(who, 1, Int::plus)
            out += h.id
            return true
        }
        // Round robin: one from each list in turn, until none has anything left to give.
        val turns = genreLists + listOf(artistList)
        val cursors = IntArray(turns.size)
        var progressed = true
        while (out.size < limit && progressed) {
            progressed = false
            turns.forEachIndexed { i, list ->
                while (cursors[i] < list.size) {
                    val taken = take(list[cursors[i]++])
                    if (taken) { progressed = true; break }
                }
            }
        }
        plain.forEach { take(it) }
        return out
    }

    /** Takes turns across [lists], one from each, front to back. */
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (lists.any { i < it.size }) {
            lists.forEach { if (i < it.size) out += it[i] }
            i++
        }
        return out
    }

    private fun fold(text: String) = DjRequestParser.fold(text)

    const val MAX_ARTISTS = 8
    const val MAX_GENRES = 3
    private const val ARTISTS_SEARCHED = 5
    private const val ARTIST_HITS = 2
    private const val ARTIST_EXTRAS = 6
    private const val PER_ARTIST = 2

    /** Tags that are about the listener, not the music. */
    private val NOT_GENRES = setOf("seen live", "favorites", "favourites", "male vocalists", "female vocalists", "spotify", "all")
}
