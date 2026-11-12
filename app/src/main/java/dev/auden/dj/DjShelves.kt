package dev.auden.dj

import java.util.Locale
import java.util.Random

/** The parts of the DJ's page, in the order they are shown. */
enum class DjSection {
    /** Starting a set now: for you, a surprise, a mood, or something typed. */
    START,

    /** Sets built from what the listener plays and keeps coming back to. */
    FOR_YOU,

    /** Music that was dropped along the way. */
    COME_BACK,

    /** Music the listener has not heard, near their taste or further from it. */
    DISCOVER,

    /** Sets for the moment of the day: the same moods, ordered and explained by the hour. */
    MOMENT,

    /** Sets from the library's own artists, genres, decades and albums. */
    EXPLORE,
}

/** What a set is for. The page words each kind from it: a title, and the reason it is offered. */
@kotlinx.serialization.Serializable
enum class DjCardKind {
    FOR_YOU, SURPRISE, MOOD,
    FAVORITES, TOP_ARTIST, HABITUAL_GENRE,
    LOST_SONGS, LOST_ARTIST, LOST_ALBUM,
    DISCOVER_NEAR, DISCOVER_ARTIST, DISCOVER_FAR,
    MOMENT,
    THEME_ARTIST, THEME_GENRE, THEME_DECADE, THEME_ALBUM,
}

/**
 * A set the DJ offers: what it is for ([kind], [subject], [number]), who is in it ([preview]), and how it starts
 * ([request], which [DjSession.start] takes as it is). A set is not a playlist: it has no fixed songs, only the
 * intention and the data behind it, and the songs and their order are the DJ's when it is played.
 */
data class DjSetCard(
    /** Stable between builds of the page, so a regenerated card keeps its place and a saved one is recognised. */
    val id: String,
    val section: DjSection,
    val kind: DjCardKind,
    /** The artist, genre, album or mood it is about, when it is about one thing. */
    val subject: String? = null,
    /** A figure its reason uses: months away, the decade, the plays this month. */
    val number: Int? = null,
    val mood: DjMood? = null,
    /** A few of the artists in it, drawn again by [DjShelves.build]'s `variants`. */
    val preview: List<String> = emptyList(),
    /** Covers of songs in it (artwork urls), the likeliest to be recognised first: up to four, drawn again with the set. */
    val covers: List<String> = emptyList(),
    /** How many songs of the library are behind it. */
    val songs: Int = 0,
    /** Null for the ones the DJ chooses itself: [DjCardKind.FOR_YOU], and [DjCardKind.SURPRISE] at the moment it is pressed. */
    val request: DjRequest? = null,
    /** Whether it can be drawn again for another version of the same idea. */
    val regenerable: Boolean = true,
)

/** The page: each section with the sets that earned a place in it. A section with no sets is not here at all. */
class DjHome(val sections: List<Pair<DjSection, List<DjSetCard>>>) {
    fun cards(section: DjSection): List<DjSetCard> = sections.firstOrNull { it.first == section }?.second.orEmpty()
    val all: List<DjSetCard> get() = sections.flatMap { it.second }
}

/**
 * Builds what the DJ's page offers from the pool the DJ itself plays from ([DjPool]): nothing here reads the
 * disk, the network or the clock. Every set has a reason in the data — a count, a date, a tag — and a section
 * or a set the data cannot support is left out rather than shown empty. Sets that would mostly be the same
 * songs as one already offered are left out too, and an artist or an album is not the subject of two sets.
 *
 * The moods, the time of day and the request a set starts with are the planner's own ([DjPlanner],
 * [DjRequest]); this only decides which of them are worth offering to this listener now.
 */
object DjShelves {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val LOST_SONG_AFTER_DAYS = 90
    private const val LOST_ARTIST_AFTER_DAYS = 60
    private const val STREAMED_FOR_DISCOVERY = 8
    private const val OVERLAP_LIMIT = 0.7

    /**
     * [variants] says how many times each set (by its id) has been drawn again: another artist, another album,
     * another few names in the preview. [genresOf] reads an artist's Last.fm genres for the moods, as the planner does.
     */
    fun build(
        tracks: List<DjTrack>,
        now: Long,
        hour: Int,
        weekend: Boolean,
        variants: Map<String, Int> = emptyMap(),
        genresOf: (String) -> List<String> = { emptyList() },
    ): DjHome {
        val min = DjPlanner.MIN_BLOCK
        // Nothing the DJ could make a set of: nothing to offer, and it says so by having nothing in it.
        if (distinctArtists(tracks) < min) return DjHome(emptyList())

        val day = now / DAY_MS
        fun rng(id: String) = Random(id.hashCode() * 31L + (variants[id] ?: 0) * 7919L + day)
        fun variant(id: String) = variants[id] ?: 0
        fun preview(id: String, of: List<DjTrack>): List<String> =
            of.map { it.leadArtist }.distinct().shuffled(kotlin.random.Random(rng(id).nextLong())).take(3)
        // The covers of what the listener knows best, a few of them drawn at random so a set drawn again shows others.
        fun covers(id: String, of: List<DjTrack>, n: Int = 4): List<String> =
            of.sortedByDescending { it.plays * 2 + it.plays30 * 3 }.mapNotNull { it.art?.takeIf(String::isNotBlank) }
                .distinct().take(n * 3).shuffled(kotlin.random.Random(rng(id).nextLong())).take(n)

        val taken = ArrayList<Set<String>>()
        val usedArtists = HashSet<String>()
        val usedAlbums = HashSet<String>()
        var usedGenre: String? = null
        /** Whether [ids] is mostly a set already offered; if not, it is remembered as offered. */
        fun fresh(ids: Set<String>): Boolean {
            if (taken.any { overlap(it, ids) > OVERLAP_LIMIT }) return false
            taken += ids
            return true
        }

        val sections = ArrayList<Pair<DjSection, List<DjSetCard>>>()

        // ── Start ─────────────────────────────────────────────────────────
        val moods = DjMood.entries.mapNotNull { mood ->
            val of = tracks.filter { DjPlanner.matchesMood(it, mood, genresOf(it.leadArtist)) }
            if (distinctArtists(of) >= min) mood to of else null
        }
        val start = ArrayList<DjSetCard>()
        start += DjSetCard(
            "for-you", DjSection.START, DjCardKind.FOR_YOU, regenerable = false,
            covers = covers("for-you", tracks.filter { it.plays >= 3 }.ifEmpty { tracks }),
        )
        start += DjSetCard("surprise", DjSection.START, DjCardKind.SURPRISE, regenerable = false)
        moods.forEach { (mood, of) ->
            start += DjSetCard(
                "start-${mood.name}", DjSection.START, DjCardKind.MOOD, mood = mood, songs = of.size,
                request = DjRequest(text = mood.name.lowercase(Locale.ROOT), mood = mood), regenerable = false,
                covers = covers("start-${mood.name}", of, 1),
            )
        }
        sections += DjSection.START to start

        // ── Made for you ──────────────────────────────────────────────────
        val forYou = ArrayList<DjSetCard>()
        val favorites = tracks.filter { it.plays >= 3 }
        if (distinctArtists(favorites) >= min && fresh(favorites.ids())) {
            forYou += DjSetCard(
                "favorites", DjSection.FOR_YOU, DjCardKind.FAVORITES, songs = favorites.size,
                preview = preview("favorites", favorites), covers = covers("favorites", favorites),
                request = DjRequest(text = "favorites", favorites = true),
            )
        }
        // The artists of this month: where the listener's attention has been, not where it was once.
        val monthly = tracks.groupBy { it.leadArtist.lowercase(Locale.ROOT) }
            .filterValues { ts -> ts.size >= min && ts.sumOf { it.plays30 } >= 6 }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays30 } }
        for ((n, entry) in rotate(monthly, variant("top-artist")).take(1).withIndex()) {
            val of = entry.value
            val artist = of.first().leadArtist
            if (!fresh(of.ids())) continue
            usedArtists += artist.lowercase(Locale.ROOT)
            forYou += DjSetCard(
                "top-artist-$n", DjSection.FOR_YOU, DjCardKind.TOP_ARTIST, subject = artist,
                number = of.sumOf { it.plays30 }, songs = of.size, covers = covers("top-artist-$n", of),
                request = DjRequest(text = artist, artist = artist),
            )
        }
        // The genre the listener lives in, when one stands out: a mix of their habits rather than of one artist.
        val byGenre = tracks.filter { it.genreKey != null }.groupBy { it.genreKey!! }
            // A habit is a history: a genre with hardly a play in it is not what the listener lives in.
            .filterValues { ts -> ts.size >= 8 && distinctArtists(ts) >= min && ts.sumOf { it.plays } >= 30 }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
        byGenre.firstOrNull()?.let { (genre, of) ->
            if (fresh(of.ids())) {
                usedGenre = genre
                forYou += DjSetCard(
                    "habitual-genre", DjSection.FOR_YOU, DjCardKind.HABITUAL_GENRE, subject = genre.replaceFirstChar { it.uppercase() },
                    songs = of.size, preview = preview("habitual-genre", of), covers = covers("habitual-genre", of),
                    request = DjRequest(text = genre, genre = genre),
                )
            }
        }
        if (forYou.isNotEmpty()) sections += DjSection.FOR_YOU to forYou

        // ── Come back to ──────────────────────────────────────────────────
        val comeBack = ArrayList<DjSetCard>()
        val lostSongs = tracks.filter { it.plays >= 5 && it.lastPlayedAt in 1 until now - LOST_SONG_AFTER_DAYS * DAY_MS }
        if (distinctArtists(lostSongs) >= min && fresh(lostSongs.ids())) {
            val months = (lostSongs.map { it.lastPlayedAt }.sorted()[lostSongs.size / 2].let { (now - it) / DAY_MS / 30 }).toInt()
            comeBack += DjSetCard(
                "lost-songs", DjSection.COME_BACK, DjCardKind.LOST_SONGS, number = months, songs = lostSongs.size,
                preview = preview("lost-songs", lostSongs), covers = covers("lost-songs", lostSongs),
                request = DjRequest(text = "rediscover", rediscover = true),
            )
        }
        val lostArtists = tracks.groupBy { it.leadArtist.lowercase(Locale.ROOT) }
            .filterValues { ts ->
                ts.size >= min && ts.sumOf { it.plays } >= 12 && ts.maxOf { it.lastPlayedAt } in 1 until now - LOST_ARTIST_AFTER_DAYS * DAY_MS
            }
            .filterKeys { it !in usedArtists }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
        for ((n, entry) in rotate(lostArtists, variant("lost-artist")).take(1).withIndex()) {
            val of = entry.value
            val artist = of.first().leadArtist
            if (!fresh(of.ids())) continue
            usedArtists += artist.lowercase(Locale.ROOT)
            comeBack += DjSetCard(
                "lost-artist-$n", DjSection.COME_BACK, DjCardKind.LOST_ARTIST, subject = artist,
                number = ((now - of.maxOf { it.lastPlayedAt }) / DAY_MS / 30).toInt(), songs = of.size,
                covers = covers("lost-artist-$n", of), request = DjRequest(text = artist, artist = artist),
            )
        }
        val lostAlbums = tracks.filter { !it.album.isNullOrBlank() }.groupBy { it.album!!.lowercase(Locale.ROOT) to it.leadArtist.lowercase(Locale.ROOT) }
            .filterValues { ts ->
                ts.size >= min && ts.sumOf { it.plays } >= 6 && ts.maxOf { it.lastPlayedAt } in 1 until now - LOST_ARTIST_AFTER_DAYS * DAY_MS
            }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
        for ((n, entry) in rotate(lostAlbums, variant("lost-album")).take(1).withIndex()) {
            val of = entry.value
            val album = of.first().album!!
            if (album.lowercase(Locale.ROOT) in usedAlbums) continue
            if (!fresh(of.ids())) continue
            usedAlbums += album.lowercase(Locale.ROOT)
            comeBack += DjSetCard(
                "lost-album-$n", DjSection.COME_BACK, DjCardKind.LOST_ALBUM, subject = album,
                number = ((now - of.maxOf { it.lastPlayedAt }) / DAY_MS / 30).toInt(), songs = of.size,
                preview = listOf(of.first().leadArtist), covers = covers("lost-album-$n", of, 1),
                request = DjRequest(text = album, album = album, artist = of.first().leadArtist),
            )
        }
        if (comeBack.isNotEmpty()) sections += DjSection.COME_BACK to comeBack

        // ── Discover ──────────────────────────────────────────────────────
        // Found on YouTube Music's radio of what the listener streams, so it needs some streamed history to follow.
        val streamed = tracks.filter { it.plays > 0 && it.localUri == null }
        if (streamed.size >= STREAMED_FOR_DISCOVERY) {
            val discover = ArrayList<DjSetCard>()
            discover += DjSetCard(
                "discover-near", DjSection.DISCOVER, DjCardKind.DISCOVER_NEAR, regenerable = false,
                covers = covers("discover-near", streamed, 3), request = DjRequest(text = "discover", discover = true),
            )
            val loved = streamed.groupBy { it.leadArtist }.entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
            if (loved.isNotEmpty()) {
                val pick = rotate(loved, variant("discover-artist")).first()
                discover += DjSetCard(
                    "discover-artist", DjSection.DISCOVER, DjCardKind.DISCOVER_ARTIST, subject = pick.key,
                    covers = covers("discover-artist", pick.value, 3),
                    request = DjRequest(text = pick.key, discover = true, discoverSeed = pick.key),
                )
            }
            if (streamed.count { it.plays in 1..6 } >= STREAMED_FOR_DISCOVERY / 2) {
                discover += DjSetCard(
                    "discover-far", DjSection.DISCOVER, DjCardKind.DISCOVER_FAR, regenerable = false,
                    covers = covers("discover-far", streamed.filter { it.plays in 1..6 }, 3),
                    request = DjRequest(text = "discover far", discover = true, discoverFar = true),
                )
            }
            sections += DjSection.DISCOVER to discover
        }

        // ── By the moment ─────────────────────────────────────────────────
        val moment = moods.sortedByDescending { (mood, _) -> DjPlanner.moodPrior(mood, hour, weekend) }
            .take(3)
            .map { (mood, of) ->
                DjSetCard(
                    "moment-${mood.name}", DjSection.MOMENT, DjCardKind.MOMENT, mood = mood, number = momentOf(hour),
                    songs = of.size, preview = preview("moment-${mood.name}", of), covers = covers("moment-${mood.name}", of),
                    request = DjRequest(text = mood.name.lowercase(Locale.ROOT), mood = mood),
                )
            }
        if (moment.isNotEmpty()) sections += DjSection.MOMENT to moment

        // ── Explore your music ────────────────────────────────────────────
        val explore = ArrayList<DjSetCard>()
        val artists = tracks.groupBy { it.leadArtist.lowercase(Locale.ROOT) }
            .filterValues { it.size >= 6 }
            .filterKeys { it !in usedArtists }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
            .take(12)
        for ((n, entry) in rotate(artists, variant("theme-artist")).take(3).withIndex()) {
            val of = entry.value
            val artist = of.first().leadArtist
            if (!fresh(of.ids())) continue
            usedArtists += artist.lowercase(Locale.ROOT)
            explore += DjSetCard(
                "theme-artist-$n", DjSection.EXPLORE, DjCardKind.THEME_ARTIST, subject = artist, songs = of.size,
                covers = covers("theme-artist-$n", of, 1), request = DjRequest(text = artist, artist = artist),
            )
        }
        val genres = tracks.filter { it.genreKey != null && it.genreKey != usedGenre }.groupBy { it.genreKey!! }
            .filterValues { ts -> ts.size >= 8 && distinctArtists(ts) >= min }
            .entries.sortedByDescending { e -> e.value.size }
        for ((n, entry) in rotate(genres, variant("theme-genre")).take(2).withIndex()) {
            if (!fresh(entry.value.ids())) continue
            explore += DjSetCard(
                "theme-genre-$n", DjSection.EXPLORE, DjCardKind.THEME_GENRE, subject = entry.key.replaceFirstChar { it.uppercase() },
                songs = entry.value.size, preview = preview("theme-genre-$n", entry.value),
                covers = covers("theme-genre-$n", entry.value), request = DjRequest(text = entry.key, genre = entry.key),
            )
        }
        val decades = tracks.filter { it.decade != null }.groupBy { it.decade!! }
            .filterValues { ts -> ts.size >= 8 && distinctArtists(ts) >= min }
            .entries.sortedByDescending { e -> e.value.size }
        for ((n, entry) in rotate(decades, variant("theme-decade")).take(2).withIndex()) {
            if (!fresh(entry.value.ids())) continue
            explore += DjSetCard(
                "theme-decade-$n", DjSection.EXPLORE, DjCardKind.THEME_DECADE, number = entry.key, songs = entry.value.size,
                preview = preview("theme-decade-$n", entry.value), covers = covers("theme-decade-$n", entry.value),
                request = DjRequest(text = "${entry.key}s", decade = entry.key),
            )
        }
        val albums = tracks.filter { !it.album.isNullOrBlank() }.groupBy { it.album!!.lowercase(Locale.ROOT) to it.leadArtist.lowercase(Locale.ROOT) }
            .filterValues { it.size >= 5 }
            .filterKeys { it.first !in usedAlbums }
            .entries.sortedByDescending { e -> e.value.sumOf { it.plays } }
        for ((n, entry) in rotate(albums, variant("theme-album")).take(2).withIndex()) {
            val of = entry.value
            if (!fresh(of.ids())) continue
            explore += DjSetCard(
                "theme-album-$n", DjSection.EXPLORE, DjCardKind.THEME_ALBUM, subject = of.first().album, songs = of.size,
                preview = listOf(of.first().leadArtist), covers = covers("theme-album-$n", of, 1),
                request = DjRequest(text = of.first().album!!, album = of.first().album, artist = of.first().leadArtist),
            )
        }
        if (explore.isNotEmpty()) sections += DjSection.EXPLORE to explore

        return DjHome(sections)
    }

    /**
     * The request the DJ makes when it is told to surprise: a set it offers on the page, drawn at random,
     * leaving out the ones started lately ([recent], by card id). Null when the page has nothing to draw from.
     */
    fun surprise(home: DjHome, recent: Set<String>, rng: Random): DjSetCard? {
        val pool = home.all.filter { it.section != DjSection.START && it.request != null }
        val fresh = pool.filter { it.id !in recent }.ifEmpty { pool }
        if (fresh.isEmpty()) return null
        // The kinds of sets that are the least obvious are the likelier: a surprise is not the favourites again.
        val weights = fresh.map {
            when (it.section) {
                DjSection.EXPLORE, DjSection.DISCOVER -> 1.3
                DjSection.COME_BACK -> 1.2
                DjSection.MOMENT -> 1.0
                else -> 0.7
            }
        }
        var roll = rng.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) {
            roll -= w
            if (roll <= 0) return fresh[i]
        }
        return fresh.last()
    }

    /** 0 morning, 1 afternoon, 2 evening, 3 night: the same hours the DJ's greeting and moments use. */
    fun momentOf(hour: Int): Int = when (hour) {
        in 5..11 -> 0
        in 12..18 -> 1
        in 19..22 -> 2
        else -> 3
    }

    private fun distinctArtists(tracks: List<DjTrack>): Int = tracks.mapTo(HashSet()) { it.leadArtist.lowercase(Locale.ROOT) }.size

    private fun List<DjTrack>.ids(): Set<String> = mapTo(HashSet()) { it.id }

    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        // Of everything the two hold between them, how much is in both: one artist's songs inside the whole
        // favourites are not "the same set", two sets that share most of what they hold are.
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    /** [list] turned [by] places, so a set drawn again takes the next candidate and the page changes. */
    private fun <T> rotate(list: List<T>, by: Int): List<T> {
        if (list.isEmpty()) return list
        val n = by % list.size
        return list.drop(n) + list.take(n)
    }
}
