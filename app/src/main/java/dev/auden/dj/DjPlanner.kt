package dev.auden.dj

import java.util.Locale
import java.util.Random
import kotlin.math.ln

/**
 * Chooses what the DJ plays: one theme at a time, and songs that all belong to it.
 *
 * Coherence is by construction, not by score. A theme is a rule over tracks that carry the fact it
 * needs (an artist, a year, a genre tag, a mood keyword in the tags, a play history), and a block
 * is made only of tracks that obey the rule — so whatever the DJ says about the block is true of
 * every song in it. Scoring only decides *which* theme comes next and *which of its songs* lead.
 *
 * Built for speed on a phone: one pass groups the pool by attribute, each candidate theme is a
 * list that already exists, and nothing here touches the disk, the network or the audio. The
 * pool is capped by the caller.
 */
class DjPlanner(
    pool: List<DjTrack>,
    private val now: Long,
    seed: Long,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    val request: DjRequest? = null,
) {
    private val rng = Random(seed)

    /** What [request] could not be satisfied with, so the DJ can say so rather than pretend. */
    var relaxed: Boolean = false
        private set

    private val scope: List<DjTrack> = scoped(pool, request)

    private val used = HashSet<String>()
    private val recentThemes = ArrayDeque<DjTheme>()
    private val bias = HashMap<String, Double>()
    private val avoidedArtists = HashSet<String>()
    private var previous: DjBlock? = null

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

    /** The next block, or null when the pool has nothing left that makes a themed block of at least [MIN_BLOCK]. */
    fun next(): DjBlock? {
        val candidates = themes().mapNotNull { theme ->
            val tracks = eligible(theme)
            if (tracks.size < MIN_BLOCK) null else Candidate(theme, tracks)
        }
        if (candidates.isEmpty()) return null

        val maxAffinity = candidates.maxOf { affinity(it.tracks) }.coerceAtLeast(1e-6)
        val scored = candidates.map { c ->
            c to (
                0.45 * affinity(c.tracks) / maxAffinity +
                    0.15 * novelty(c.theme) +
                    0.25 * relatedness(c.tracks) +
                    0.15 * requestMatch(c.theme)
                ) * (bias[c.theme.key] ?: 1.0) * artistBias(c.theme) * (0.9 + 0.2 * rng.nextDouble())
        }.sortedByDescending { it.second }

        // Weighted pick among the best three: the top theme most of the time, not always.
        val top = scored.take(3)
        var roll = rng.nextDouble() * top.sumOf { it.second }
        var chosen = top.first().first
        for ((candidate, score) in top) {
            roll -= score
            if (roll <= 0) { chosen = candidate; break }
        }

        val tracks = assemble(chosen.theme, chosen.tracks)
        used += tracks.map { it.id }
        recentThemes.addLast(chosen.theme)
        while (recentThemes.size > RECENT_THEMES) recentThemes.removeFirst()
        return DjBlock(chosen.theme, tracks).also { previous = it }
    }

    private class Candidate(val theme: DjTheme, val tracks: List<DjTrack>)

    // ── Themes ──────────────────────────────────────────────────────────────

    private fun themes(): List<DjTheme> {
        val out = ArrayList<DjTheme>()
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
        // Not twice running, and not what was just played: the recent themes sit out.
        val recent = recentThemes.map { it.key }.toSet()
        val lastKind = previous?.theme?.kind
        val fresh = out.filter { it.key !in recent && (it.kind != lastKind || request?.understood == true) }
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
    }

    private fun eligible(theme: DjTheme): List<DjTrack> = scope.filter {
        it.id !in used && it.leadArtist.lowercase(Locale.ROOT) !in avoidedArtists && matches(theme, it)
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    /** How much the listener has lived with the best of this theme's songs. */
    private fun affinity(tracks: List<DjTrack>): Double = tracks
        .map { ln(1.0 + it.plays) + 1.5 * ln(1.0 + it.plays30) }
        .sortedDescending().take(blockSize).sum()

    private fun novelty(theme: DjTheme) = if (theme.kind == previous?.theme?.kind) 0.3 else 1.0

    /** Overlap of artists, genres and decades with the block before: a neighbour sounds like a continuation. */
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
            is DjTheme.ByAlbum -> 0.0
        }
    }

    private fun artistBias(theme: DjTheme): Double =
        if (theme is DjTheme.ByArtist) bias["artist:${theme.artist.lowercase(Locale.ROOT)}"] ?: 1.0 else 1.0

    // ── Assembling a block ──────────────────────────────────────────────────

    /** The songs of a block: the ones the listener knows best, no artist more than twice, in a flowing order. */
    private fun assemble(theme: DjTheme, eligible: List<DjTrack>): List<DjTrack> {
        val n = minOf(blockSize, eligible.size)
        if (theme is DjTheme.ByAlbum) return eligible.sortedBy { it.trackNumber ?: Int.MAX_VALUE }.take(n)
        if (theme == DjTheme.Fresh) return eligible.sortedByDescending { it.addedAt }.take(n)

        val ranked = eligible.sortedByDescending { fit(it) + 0.3 * rng.nextDouble() }
        val perArtist = HashMap<String, Int>()
        val picked = ArrayList<DjTrack>(n)
        val cap = if (theme is DjTheme.ByArtist) n else 2
        for (t in ranked) {
            if (picked.size == n) break
            val a = t.leadArtist.lowercase(Locale.ROOT)
            if ((perArtist[a] ?: 0) >= cap) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            picked += t
        }
        // Short of n only when the cap bit: fill from what was skipped rather than under-deliver.
        if (picked.size < n) ranked.filter { it !in picked }.take(n - picked.size).forEach { picked += it }
        return flow(picked)
    }

    private fun fit(t: DjTrack): Double = ln(1.0 + t.plays) + ln(1.0 + t.plays30)

    /**
     * Opens on the best-known song and then walks to the nearest neighbour each time — same
     * album last, and a tempo within reach when both are known — never the same artist or album
     * twice running if there is a way around it.
     */
    private fun flow(tracks: List<DjTrack>): List<DjTrack> {
        if (tracks.size <= 2) return tracks
        val left = tracks.toMutableList()
        val out = ArrayList<DjTrack>(tracks.size)
        out += left.removeAt(left.indices.maxByOrNull { fit(left[it]) }!!)
        while (left.isNotEmpty()) {
            val last = out.last()
            val next = left.minByOrNull { distance(last, it) }!!
            left.remove(next)
            out += next
        }
        return out
    }

    private fun distance(a: DjTrack, b: DjTrack): Double {
        var d = 0.0
        if (a.leadArtist.equals(b.leadArtist, ignoreCase = true)) d += 2.0
        if (!a.album.isNullOrBlank() && a.album.equals(b.album, ignoreCase = true)) d += 1.0
        if (a.bpm != null && b.bpm != null) d += kotlin.math.abs(a.bpm - b.bpm) / 40.0
        return d + 0.01 * rng.nextDouble()
    }

    // ── The request ─────────────────────────────────────────────────────────

    private fun scoped(pool: List<DjTrack>, request: DjRequest?): List<DjTrack> {
        if (request == null || !request.understood) return pool
        // Hard filters in order of how much the listener would give up: the file requirement first,
        // the mood next, then the tags, and the artist last. Each step relaxes one.
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

    private fun moodOf(t: DjTrack, mood: DjMood): Boolean {
        val text = listOfNotNull(t.genre, t.title, t.album).joinToString(" ").lowercase(Locale.ROOT)
        return mood.keywords.any { it in text }
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 5
        const val MIN_BLOCK = 4
        private const val RECENT_THEMES = 4
        private const val FAVORITE_MIN_PLAYS = 3
        private const val REDISCOVER_MIN_PLAYS = 5
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val REDISCOVER_AFTER_MS = 90 * DAY_MS
        private const val FRESH_MS = 45 * DAY_MS
    }
}
