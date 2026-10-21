package dev.auden.dj

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Chooses what the DJ plays, one set at a time.
 *
 * Two kinds of set. A **request** (typed, or "more of this") is followed literally: its songs are
 * the ones that match it, and a set may repeat an artist or an album when the listener asked for
 * exactly that. A **surprise** has no request, and is built to be listened to: a theme that fits the
 * time of day and the week, a mix of what the listener knows and what they have not heard, no artist
 * twice in a set, no artist again for two sets, and no album back to back. Coherence is by
 * construction in both: a set is made only of songs that obey its theme, and the words the DJ says
 * about it are true of every song in it.
 *
 * Nothing here touches disk, network or audio. Scoring reads the pool once per set.
 */
class DjPlanner(
    pool: List<DjTrack>,
    private val now: Long,
    seed: Long,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    request: DjRequest? = null,
    /** An artist's Last.fm genres, for reading a mood beyond the title. Empty when unknown. */
    private val genresOf: (String) -> List<String> = { emptyList() },
    /** Songs the DJ played recently, in earlier sessions too: chosen last, so a new session sounds new. */
    private val recently: Set<String> = emptySet(),
) {
    private val rng = Random(seed)
    private val pool = pool.toMutableList()

    /** What the listener asked for; null on a surprise. [setRequest] changes it from the next set on. */
    var request: DjRequest? = request
        private set

    /** Set when the request could not be met as asked, so the DJ can say so rather than pretend. */
    var relaxed: Boolean = false
        private set

    private var scope: List<DjTrack> = scoped(this.pool, request)
    private val used = HashSet<String>()
    private val recentThemes = ArrayDeque<DjTheme>()
    private val recentArtists = ArrayDeque<Set<String>>()
    private val bias = HashMap<String, Double>()
    private val avoidedArtists = HashSet<String>()
    private var previous: DjBlock? = null

    private val hour: Int = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
    private val weekend: Boolean = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).dayOfWeek.value >= 6

    fun setRequest(next: DjRequest?) {
        request = next
        scope = scoped(pool, next)
        relaxed = false
    }

    /** Songs found on the radio of something the listener knows; they join the pool for surprises. */
    fun addDiscoveries(found: List<DjTrack>) {
        val known = pool.mapTo(HashSet()) { it.id }
        found.filter { it.id !in known }.forEach(pool::add)
        scope = scoped(pool, request)
    }

    /** Frees songs of a set that was planned but never reached, so they may be chosen again. */
    fun release(ids: Collection<String>) {
        used.removeAll(ids.toSet())
    }

    /** The listener wants more like [track]: its artist, genre and decade weigh more from here on. */
    fun more(track: DjTrack) {
        boost("artist:${track.leadArtist.lowercase(Locale.ROOT)}", 1.5)
        track.genreKey?.let { boost("genre:$it", 1.3) }
        track.decade?.let { boost("decade:$it", 1.2) }
    }

    /** The listener wants less like [track]: its artist leaves the session, its genre and decade weigh less. */
    fun less(track: DjTrack) {
        avoidedArtists += track.leadArtist.lowercase(Locale.ROOT)
        track.genreKey?.let { boost("genre:$it", 0.6) }
        track.decade?.let { boost("decade:$it", 0.8) }
    }

    private fun boost(key: String, factor: Double) { bias[key] = (bias[key] ?: 1.0) * factor }

    /** The next set, or null when the pool has nothing left that makes a set of at least [MIN_BLOCK]. */
    fun next(): DjBlock? {
        val surprise = request == null
        // Themes whose artists are resting are passed over while any other theme can make a set; only
        // when none can does a theme go back to its artists, so a surprise never turns into a repeat by default.
        val candidates = candidatesFor(surprise, allowResting = false).ifEmpty { candidatesFor(surprise, allowResting = true) }
        if (candidates.isEmpty()) return null

        val maxAffinity = candidates.maxOf { affinity(it.tracks) }.coerceAtLeast(1e-6)
        val scored = candidates.map { c ->
            val score = if (surprise) {
                prior(c.theme) * (0.5 + 0.5 * affinity(c.tracks) / maxAffinity) * novelty(c.theme) *
                    (0.85 + 0.3 * rng.nextDouble())
            } else {
                (0.45 * affinity(c.tracks) / maxAffinity + 0.15 * novelty(c.theme) +
                    0.25 * relatedness(c.tracks) + 0.15 * requestMatch(c.theme)) *
                    (bias[c.theme.key] ?: 1.0) * artistBias(c.theme) * (0.9 + 0.2 * rng.nextDouble())
            }
            c to score
        }.sortedByDescending { it.second }

        // A weighted pick among the best three: the top theme most of the time, not every time.
        val top = scored.take(3)
        var roll = rng.nextDouble() * top.sumOf { it.second }
        var chosen = top.first().first
        for ((candidate, score) in top) {
            roll -= score
            if (roll <= 0) { chosen = candidate; break }
        }

        val tracks = assemble(chosen.theme, chosen.tracks, surprise)
        used += tracks.map { it.id }
        recentThemes.addLast(chosen.theme)
        while (recentThemes.size > RECENT_THEMES) recentThemes.removeFirst()
        recentArtists.addLast(tracks.map { it.leadArtist.lowercase(Locale.ROOT) }.toSet())
        while (recentArtists.size > ARTIST_COOLDOWN_SETS) recentArtists.removeFirst()
        return DjBlock(chosen.theme, tracks).also { previous = it }
    }

    private class Candidate(val theme: DjTheme, val tracks: List<DjTrack>)

    private fun candidatesFor(surprise: Boolean, allowResting: Boolean): List<Candidate> =
        themes(surprise).mapNotNull { theme ->
            val tracks = eligible(theme, surprise, allowResting)
            // A surprise needs enough different artists to give each one place; a theme that is one
            // artist's alone is not a surprise, whatever its songs.
            val enough = if (surprise) distinctArtists(tracks) >= MIN_BLOCK else tracks.size >= MIN_BLOCK
            if (!enough) null else Candidate(theme, tracks)
        }

    // ── Themes ──────────────────────────────────────────────────────────────

    private fun themes(surprise: Boolean): List<DjTheme> {
        val out = ArrayList<DjTheme>()
        if (surprise) {
            // Moods and moments, not artists: a surprise is a feeling, and the listener's own history.
            DjMood.entries.forEach { out += DjTheme.ByMood(it) }
            out += DjTheme.Favorites
            out += DjTheme.Rediscover
            out += DjTheme.Fresh
            if (pool.any { it.discovery }) out += DjTheme.Discover
            // The listener's own playlists: a wide, personal source that is not just what they played most.
            scope.flatMap { it.playlists }.groupingBy { it }.eachCount()
                .forEach { (name, n) -> if (n >= MIN_BLOCK) out += DjTheme.ByPlaylist(name) }
        } else {
            scope.groupBy { it.leadArtist.lowercase(Locale.ROOT) }.forEach { (_, ts) ->
                if (ts.size >= MIN_BLOCK) out += DjTheme.ByArtist(ts.first().leadArtist)
            }
            scope.mapNotNull { it.decade }.groupingBy { it }.eachCount().forEach { (d, n) -> if (n >= MIN_BLOCK) out += DjTheme.ByDecade(d) }
            scope.mapNotNull { it.genreKey }.groupingBy { it }.eachCount().forEach { (g, n) -> if (n >= MIN_BLOCK) out += DjTheme.ByGenre(g) }
            DjMood.entries.forEach { mood -> if (scope.count { moodOf(it, mood) } >= MIN_BLOCK) out += DjTheme.ByMood(mood) }
            scope.filter { !it.album.isNullOrBlank() }.groupBy { it.album!!.lowercase(Locale.ROOT) to it.leadArtist.lowercase(Locale.ROOT) }
                .forEach { (_, ts) -> if (ts.size >= MIN_BLOCK) out += DjTheme.ByAlbum(ts.first().album!!, ts.first().leadArtist) }
            out += DjTheme.Favorites
            out += DjTheme.Rediscover
            out += DjTheme.Fresh
        }
        // Not twice running, and not the same family back to back, unless there is no other choice.
        val recent = recentThemes.map { it.key }.toSet()
        val lastKind = previous?.theme?.kind
        val fresh = out.filter { it.key !in recent && (it.kind != lastKind || !surprise && request?.understood == true) }
        return fresh.ifEmpty { out.filter { it.key !in recent } }
    }

    private fun matches(theme: DjTheme, t: DjTrack): Boolean = when (theme) {
        is DjTheme.ByArtist -> t.leadArtist.equals(theme.artist, ignoreCase = true)
        is DjTheme.ByDecade -> t.decade == theme.decade
        is DjTheme.ByGenre -> t.genreKey == theme.genre
        is DjTheme.ByMood -> moodOf(t, theme.mood)
        is DjTheme.ByAlbum -> t.album.equals(theme.album, ignoreCase = true) && t.leadArtist.equals(theme.artist, ignoreCase = true)
        DjTheme.Favorites -> t.plays >= FAVORITE_MIN_PLAYS
        DjTheme.Rediscover -> t.plays >= REDISCOVER_MIN_PLAYS && t.lastPlayedAt in 1 until now - REDISCOVER_AFTER_MS
        DjTheme.Fresh -> t.addedAt > 0 && now - t.addedAt <= FRESH_MS && t.localUri != null
        DjTheme.Discover -> t.discovery
        is DjTheme.ByPlaylist -> t.playlists.any { it.equals(theme.name, ignoreCase = true) }
    }

    /** The songs of [theme] still free to play; a surprise also leaves out artists of the last sets. */
    private fun eligible(theme: DjTheme, surprise: Boolean, allowResting: Boolean): List<DjTrack> {
        val base = scope.filter {
            it.id !in used && it.leadArtist.lowercase(Locale.ROOT) !in avoidedArtists && matches(theme, it)
        }
        if (!surprise) return base
        // What the DJ played lately is left out while the theme has plenty else; with little, it stays in.
        val fresher = base.filter { it.id !in recently }
        val open = if (distinctArtists(fresher) >= 2 * MIN_BLOCK) fresher else base
        // A surprise rests its artists: two sets back if the theme can still make a varied set from
        // the rest, else one set back. With [allowResting] off, a theme that cannot is left out.
        val sets = recentArtists.toList()
        for (depth in sets.size downTo 1) {
            val resting = sets.takeLast(depth).flatten().toSet()
            val rested = open.filter { it.leadArtist.lowercase(Locale.ROOT) !in resting }
            if (distinctArtists(rested) >= MIN_BLOCK) return rested
        }
        return if (allowResting) open else emptyList()
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    /** How well [theme] suits this time of day and week. The one place the DJ's sense of the moment lives. */
    private fun prior(theme: DjTheme): Double {
        val morning = hour in 5..11
        val afternoon = hour in 12..18
        val evening = hour in 19..22
        return when (theme) {
            is DjTheme.ByMood -> when (theme.mood) {
                DjMood.ENERGY -> when {
                    morning -> 1.0
                    afternoon -> if (weekend) 0.9 else 0.6
                    evening -> if (weekend) 0.9 else 0.5
                    else -> 0.2
                }
                DjMood.FOCUS -> when {
                    afternoon && !weekend -> 1.0
                    morning && !weekend -> 0.7
                    else -> 0.3
                }
                DjMood.CHILL -> when {
                    evening || hour !in 5..11 -> 1.0
                    else -> 0.5
                }
                DjMood.ACOUSTIC -> when {
                    evening || hour !in 5..11 -> 0.9
                    else -> 0.6
                }
            }
            DjTheme.Favorites -> 0.6
            DjTheme.Rediscover -> if (evening || hour !in 5..11) 0.9 else 0.4
            DjTheme.Fresh -> 0.6
            DjTheme.Discover -> 0.7
            is DjTheme.ByPlaylist -> 0.9
            else -> 0.5
        }
    }

    /** How much the listener has lived with the best of this theme's songs. */
    private fun affinity(tracks: List<DjTrack>): Double = tracks
        .map { fit(it) }
        .sortedDescending().take(blockSize).sum()

    private fun fit(t: DjTrack): Double = ln(1.0 + t.plays) + 1.5 * ln(1.0 + t.plays30)

    private fun novelty(theme: DjTheme): Double = if (theme.kind == previous?.theme?.kind) 0.4 else 1.0

    /** Overlap of artists, genres and decades with the set before: a neighbour sounds like a continuation. */
    private fun relatedness(tracks: List<DjTrack>): Double {
        val before = previous ?: return 0.5
        val a = attributes(before.tracks)
        val b = attributes(tracks)
        val union = (a + b).size.toDouble()
        return if (union == 0.0) 0.0 else a.intersect(b).size / union
    }

    private fun attributes(tracks: List<DjTrack>): Set<String> = buildSet {
        tracks.forEach {
            add("artist:${it.leadArtist.lowercase(Locale.ROOT)}")
            it.genreKey?.let { g -> add("genre:$g") }
            it.decade?.let { d -> add("decade:$d") }
        }
    }

    private fun requestMatch(theme: DjTheme): Double {
        val r = request ?: return 0.0
        return when (theme) {
            DjTheme.Favorites -> if (r.favorites) 1.0 else 0.0
            DjTheme.Fresh -> if (r.fresh) 1.0 else 0.0
            DjTheme.Rediscover -> if (r.rediscover) 1.0 else 0.0
            is DjTheme.ByArtist -> if (r.artist.equals(theme.artist, ignoreCase = true)) 1.0 else 0.0
            is DjTheme.ByDecade -> if (r.decade == theme.decade) 1.0 else 0.0
            is DjTheme.ByGenre -> if (r.genre == theme.genre) 1.0 else 0.0
            is DjTheme.ByMood -> if (r.mood == theme.mood) 1.0 else 0.0
            is DjTheme.ByAlbum, DjTheme.Discover, is DjTheme.ByPlaylist -> 0.0
        }
    }

    private fun artistBias(theme: DjTheme): Double =
        if (theme is DjTheme.ByArtist) bias["artist:${theme.artist.lowercase(Locale.ROOT)}"] ?: 1.0 else 1.0

    // ── Assembling a set ────────────────────────────────────────────────────

    /**
     * The songs of a set, in a flowing order.
     *
     * A surprise: at most one song per artist, about seven in ten of them from what the listener
     * knows and the rest new to them, no album twice in a row. A request: the best matches, with the
     * artist cap the request itself implies.
     */
    private fun assemble(theme: DjTheme, eligible: List<DjTrack>, surprise: Boolean): List<DjTrack> {
        val artists = distinctArtists(eligible)
        val n = if (surprise) minOf(blockSize, artists) else minOf(blockSize, eligible.size)
        if (theme is DjTheme.ByAlbum) return eligible.sortedBy { it.trackNumber ?: Int.MAX_VALUE }.take(n)
        if (theme == DjTheme.Fresh) return eligible.sortedByDescending { it.addedAt }.take(n)

        // A surprise gives each artist one place in its set. A request keeps the artist it names, and
        // otherwise allows two of an artist.
        val artistCap = when {
            !surprise -> if (theme is DjTheme.ByArtist) n else 2
            else -> 1
        }
        // Each song's jitter is drawn once: a sort whose key changes between comparisons has no order.
        // Well-known songs are favoured, not guaranteed: a wide random spread keeps the top of the list from
        // being every set's answer, and songs heard lately sink.
        val ranked = eligible
            .map { it to 0.35 * fit(it) + 1.2 * rng.nextGaussian() - (if (it.id in recently) RECENT_PENALTY else 0.0) }
            .sortedByDescending { it.second }.map { it.first }
        val familiar = ranked.filter { !it.discovery && it.plays > 0 }
        val newer = ranked.filter { it.discovery || it.plays == 0 }
        val familiarTarget = if (surprise) (n * FAMILIAR_SHARE).roundToInt() else n

        val perArtist = HashMap<String, Int>()
        val picked = ArrayList<DjTrack>(n)
        fun take(from: List<DjTrack>, limit: Int) {
            for (t in from) {
                if (picked.size >= limit) break
                if (t in picked) continue
                val a = t.leadArtist.lowercase(Locale.ROOT)
                if ((perArtist[a] ?: 0) >= artistCap) continue
                perArtist[a] = (perArtist[a] ?: 0) + 1
                picked += t
            }
        }
        if (surprise) {
            take(familiar, familiarTarget)
            take(newer, n)
            take(familiar, n)
        } else {
            take(ranked, n)
            // A request is allowed to come up short of its caps rather than repeat an artist past them.
            if (picked.size < n) ranked.filter { it !in picked }.take(n - picked.size).forEach { picked += it }
        }
        return flow(picked, surprise)
    }

    private fun fitPlus(t: DjTrack) = fit(t)

    private fun distinctArtists(tracks: List<DjTrack>): Int =
        tracks.mapTo(HashSet()) { it.leadArtist.lowercase(Locale.ROOT) }.size

    /**
     * Opens on the best-known song, then walks to the nearest neighbour each time: never the same
     * album back to back, never the same artist back to back if there is any other way round, and a
     * tempo within reach when both are known.
     */
    private fun flow(tracks: List<DjTrack>, surprise: Boolean): List<DjTrack> {
        if (tracks.size <= 2) return tracks
        val left = tracks.toMutableList()
        val out = ArrayList<DjTrack>(tracks.size)
        out += left.removeAt(left.indices.maxByOrNull { fitPlus(left[it]) }!!)
        while (left.isNotEmpty()) {
            val last = out.last()
            val next = left.minByOrNull { distance(last, it, surprise) }!!
            left.remove(next)
            out += next
        }
        return out
    }

    private fun distance(a: DjTrack, b: DjTrack, surprise: Boolean): Double {
        var d = 0.0
        if (a.leadArtist.equals(b.leadArtist, ignoreCase = true)) d += if (surprise) 4.0 else 2.0
        if (!a.album.isNullOrBlank() && a.album.equals(b.album, ignoreCase = true)) d += if (surprise) 6.0 else 1.0
        if (a.bpm != null && b.bpm != null) d += abs(a.bpm - b.bpm) / 40.0
        return d + 0.01 * rng.nextDouble()
    }

    // ── The request ─────────────────────────────────────────────────────────

    private fun scoped(pool: List<DjTrack>, request: DjRequest?): List<DjTrack> {
        if (request == null || !request.understood) return pool
        // Hard filters in order of how much the listener would give up: the file requirement first,
        // then the mood, the genre, the decade. Each step relaxes one, and the DJ says so.
        val steps: List<(DjRequest) -> DjRequest> = listOf(
            { it },
            { it.copy(localOnly = false) },
            { it.copy(localOnly = false, mood = null) },
            { it.copy(localOnly = false, mood = null, genre = null) },
            { it.copy(localOnly = false, mood = null, genre = null, decade = null) },
        )
        for ((i, step) in steps.withIndex()) {
            val r = step(request)
            val filtered = pool.filter { passes(it, r) }
            if (filtered.size >= MIN_BLOCK) {
                relaxed = i > 0
                return filtered
            }
        }
        relaxed = true
        return pool
    }

    private fun passes(t: DjTrack, r: DjRequest): Boolean =
        (r.artist == null || t.leadArtist.equals(r.artist, ignoreCase = true)) &&
            (r.decade == null || t.decade == r.decade) &&
            (r.genre == null || t.genreKey == r.genre) &&
            (r.mood == null || moodOf(t, r.mood)) &&
            (!r.localOnly || t.localUri != null)

    /** A track's mood from its title, album, genre tag, and the artist's Last.fm genres. */
    private fun moodOf(t: DjTrack, mood: DjMood): Boolean {
        val text = (listOfNotNull(t.genre, t.title, t.album) + genresOf(t.leadArtist)).joinToString(" ").lowercase(Locale.ROOT)
        return mood.keywords.any { it in text }
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 8
        const val MIN_BLOCK = 4
        private const val RECENT_THEMES = 4
        private const val ARTIST_COOLDOWN_SETS = 2
        private const val RECENT_PENALTY = 3.0
        private const val FAMILIAR_SHARE = 0.7
        private const val FAVORITE_MIN_PLAYS = 3
        private const val REDISCOVER_MIN_PLAYS = 5
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REDISCOVER_AFTER_MS = 90 * DAY_MS
        private const val FRESH_MS = 45 * DAY_MS
    }
}
