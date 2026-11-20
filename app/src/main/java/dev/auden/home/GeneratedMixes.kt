package dev.auden.home

import android.content.Context
import android.util.Log
import com.music.bitchord.data.AppleArtistGenres
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.TrackEntry
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * The lists Auden makes for the listener each day, from what they play: **In loop** (the thirty songs they repeat
 * most), a **genre mix** of only artists they already play, **new music** along their lines, and **because you like**
 * a song they play over and over. Each is a real list — its songs are kept and its page opens like a playlist's —
 * and each is made again once a day, or the first time it can be (offline, a list that needs YouTube Music waits).
 *
 * What is played is the device's own record ([ListeningStats]); YouTube Music's radio is only asked, as nobody, for
 * songs like one the listener chose. Nothing here touches an account.
 */
object GeneratedMixes {
    /** A list's page id: a local page, so it opens on the device's own page machinery. */
    const val PAGE_PREFIX = "local:mix:"

    private const val FILE = "generated_mixes.json"
    private const val SIZE = 30

    /** Fewer songs than this and the list is not worth showing. */
    private const val MIN_SONGS = 6
    private const val LIKES_SIZE = 50
    private const val REDISCOVER_AFTER_DAYS = 45L
    private const val DAY_MS = 86_400_000L

    data class Mix(
        val id: String,
        val kind: TopPickKind,
        /** The genre, or the song a "because" list is about; empty for the others. */
        val title: String,
        /** The artists in a genre mix, or the artist of the song a "because" list is about. */
        val subtitle: String,
        val images: List<String>,
        val songs: List<Song>,
        /** The day it was made, `2026-11-20`. */
        val day: String,
    ) {
        val pageId: String get() = PAGE_PREFIX + id
    }

    private val _mixes = MutableStateFlow<List<Mix>>(emptyList())
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

    private val order = listOf(
        TopPickKind.IN_LOOP, TopPickKind.NEW_FOR_YOU, TopPickKind.LIKES_MIX, TopPickKind.BECAUSE_YOU_LIKE,
        TopPickKind.GENRE_MIX, TopPickKind.DEEP_CUTS, TopPickKind.REDISCOVER, TopPickKind.ONE_YEAR_AGO,
    )
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun get(pageId: String): Mix? = _mixes.value.firstOrNull { it.pageId == pageId }

    /** The cards for Home's Top Picks row. */
    fun picks(mixes: List<Mix> = _mixes.value): List<TopPick> = mixes.map {
        TopPick(kind = it.kind, title = it.title, subtitle = it.subtitle, images = it.images, browseId = it.pageId)
    }

    /**
     * Makes today's lists that are not made yet, and puts all of them in [mixes]. What was made on an earlier day stays
     * until it can be replaced, so a morning without a connection does not empty the row.
     */
    suspend fun ensureToday(context: Context, force: Boolean = false) = mutex.withLock {
        val file = File(context.applicationContext.filesDir, FILE)
        val today = LocalDate.now().toString()
        val stored = withContext(Dispatchers.IO) { read(file) }
        if (_mixes.value.isEmpty() && stored.isNotEmpty()) _mixes.value = stored
        val current = _mixes.value.associateBy { it.kind }
        val wanted = order.filter { force || current[it]?.day != today }
        if (wanted.isEmpty()) return@withLock
        val built = runCatching { build(context.applicationContext, today, wanted) }
            .onFailure { Log.w("GeneratedMixes", "could not build: ${it.message}") }
            .getOrDefault(emptyList())
            .associateBy { it.kind }
        val merged = order.mapNotNull { built[it] ?: current[it] }
        if (merged != _mixes.value) {
            _mixes.value = merged
            withContext(Dispatchers.IO) { write(file, merged) }
        }
    }

    // ---- Making them -------------------------------------------------------------------------------------------

    private suspend fun build(app: Context, today: String, wanted: List<TopPickKind>): List<Mix> = withContext(Dispatchers.IO) {
        val heard = ListeningStats.heardTracks().filter { it.title.isNotBlank() && it.plays > 0 }
        if (heard.isEmpty()) return@withContext emptyList()
        val cutoff = LocalDate.parse(today).minusDays(30).toString()
        fun recent(e: TrackEntry) = e.days.filterKeys { it >= cutoff }.values.sumOf { it.plays }
        val ranked = heard.sortedWith(compareByDescending<TrackEntry> { recent(it) }.thenByDescending { it.plays })
        // Changes with the day, so a list picked among a few candidates is another one tomorrow.
        val daySeed = today.hashCode().toLong().let { if (it < 0) -it else it }
        val out = mutableListOf<Mix>()
        val heardIds = heard.mapTo(HashSet()) { it.id }
        if (TopPickKind.IN_LOOP in wanted) inLoop(ranked, ::recent, today)?.let(out::add)
        if (TopPickKind.LIKES_MIX in wanted) likesMix(daySeed, today)?.let(out::add)
        if (TopPickKind.REDISCOVER in wanted) rediscover(heard, daySeed, today)?.let(out::add)
        if (TopPickKind.ONE_YEAR_AGO in wanted) oneYearAgo(heard, today)?.let(out::add)
        if (TopPickKind.GENRE_MIX in wanted) {
            val tagged = runCatching { AudenDb.get(app).tracks().all() }.getOrDefault(emptyList())
            genreMix(app, tagged, heard, daySeed, today)?.let(out::add)
        }
        // These ask YouTube Music, as nobody, for songs like the ones the listener plays; the seeds are streamed songs.
        val seeds = ranked.filter { isStream(it.id) && it.plays >= 2 }.take(8)
        val becauseSeed = seeds.getOrNull((daySeed % minOf(3, seeds.size).coerceAtLeast(1)).toInt())
        if (TopPickKind.BECAUSE_YOU_LIKE in wanted && becauseSeed != null) because(becauseSeed, today)?.let(out::add)
        if (TopPickKind.NEW_FOR_YOU in wanted) {
            newForYou(seeds.filter { it.id != becauseSeed?.id }, heardIds, daySeed, today)?.let(out::add)
        }
        if (TopPickKind.DEEP_CUTS in wanted) deepCuts(ranked, heardIds, daySeed, today)?.let(out::add)
        out
    }

    private fun inLoop(ranked: List<TrackEntry>, recent: (TrackEntry) -> Int, today: String): Mix? {
        val thirtyDays = ranked.filter { recent(it) >= 2 }.take(SIZE)
        // Not enough in the last thirty days: the most repeated of all time stands in.
        val list = if (thirtyDays.size >= MIN_SONGS) thirtyDays else ranked.filter { it.plays >= 2 }.sortedByDescending { it.plays }.take(SIZE)
        if (list.size < MIN_SONGS) return null
        val songs = list.map(::toSong)
        return Mix("loop", TopPickKind.IN_LOOP, "", "", covers(songs), songs, today)
    }

    private suspend fun genreMix(app: Context, tagged: List<dev.auden.library.db.TrackEntity>, heard: List<TrackEntry>, daySeed: Long, today: String): Mix? {
        val byArtist = heard.groupBy { it.artist.trim() }.filterKeys { it.isNotBlank() }
        val top = byArtist.entries.sortedByDescending { (_, tracks) -> tracks.sumOf { it.plays } }.take(16)
        // Genres from Last.fm when this build has its key, and from the tags of files in the library besides.
        val library = tagged.filter { !it.genre.isNullOrBlank() }.groupBy({ it.artist.trim().lowercase(Locale.ROOT) }, { it.genre!!.lowercase(Locale.ROOT) })
        seedAppleGenres(app)
        val genresOf = top.associate { (artist, _) ->
            ArtistFacts.fetchForDj(artist)
            val lastFm = ArtistFacts.genresForDj(artist)
            val files = library[artist.lowercase(Locale.ROOT)].orEmpty()
            // Apple's catalogue files every artist under one main genre, with no key: what groups them when neither
            // Last.fm (a key this build may not have) nor the files' own tags say anything.
            val apple = if (lastFm.isEmpty() && files.isEmpty()) listOfNotNull(AppleArtistGenres.genreOf(artist)) else emptyList()
            artist to (lastFm + files + apple)
                .map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() && it !in IGNORED_TAGS }.distinct().take(3)
        }
        saveAppleGenres(app)
        val weight = HashMap<String, Int>()
        val members = HashMap<String, MutableList<String>>()
        top.forEach { (artist, tracks) ->
            val plays = tracks.sumOf { it.plays }
            genresOf[artist].orEmpty().forEach { genre ->
                weight[genre] = (weight[genre] ?: 0) + plays
                members.getOrPut(genre) { mutableListOf() } += artist
            }
        }
        val genres = weight.entries.filter { (genre, _) -> members[genre].orEmpty().size >= 2 }.sortedByDescending { it.value }
        if (genres.isEmpty()) return null
        val genre = genres[(daySeed % minOf(3, genres.size)).toInt()].key
        val artists = members.getValue(genre).take(6)
        // Only these artists, their most played first, one song from each in turn.
        val queues = artists.map { artist -> byArtist.getValue(artist).sortedByDescending { it.plays }.toMutableList() }
        val picked = mutableListOf<TrackEntry>()
        while (picked.size < SIZE && queues.any { it.isNotEmpty() }) queues.forEach { q -> if (q.isNotEmpty() && picked.size < SIZE) picked += q.removeAt(0) }
        if (picked.size < MIN_SONGS) return null
        val songs = picked.map(::toSong)
        val faces = artists.mapNotNull { ArtistFacts.imageFor(it) } + covers(songs)
        val title = genre.split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
        return Mix("genre", TopPickKind.GENRE_MIX, title, artists.take(3).joinToString(", "), faces.distinct().take(4), songs, today)
    }

    private suspend fun because(seed: TrackEntry, today: String): Mix? {
        val similar = YtMusicRepository.radio(seed.id).getOrNull().orEmpty()
            .filter { it.videoId != seed.id && it.videoId.isNotBlank() }.distinctBy { it.videoId }.take(SIZE)
        if (similar.size < MIN_SONGS) return null
        // The song itself first: its cover is the one the echoes widen out of; the songs like it circle round it.
        val images = (listOfNotNull(seed.art?.takeIf { it.isNotBlank() }) + covers(similar)).distinct().take(4)
        return Mix("because", TopPickKind.BECAUSE_YOU_LIKE, seed.title, seed.artist, images, similar, today)
    }

    private suspend fun newForYou(seeds: List<TrackEntry>, heardIds: Set<String>, daySeed: Long, today: String): Mix? {
        if (seeds.isEmpty()) return null
        val start = (daySeed % seeds.size).toInt()
        val chosen = (0 until minOf(3, seeds.size)).map { seeds[(start + it) % seeds.size] }
        val lists = chosen.map { seed -> YtMusicRepository.radio(seed.id).getOrNull().orEmpty() }
        // One from each radio in turn, nothing already played, nothing twice.
        val seen = HashSet<String>()
        val songs = mutableListOf<Song>()
        var index = 0
        while (songs.size < SIZE && lists.any { index < it.size }) {
            lists.forEach { list ->
                val song = list.getOrNull(index)
                if (song != null && songs.size < SIZE && song.videoId !in heardIds && seen.add(song.videoId)) songs += song
            }
            index++
        }
        if (songs.size < MIN_SONGS) return null
        return Mix("new", TopPickKind.NEW_FOR_YOU, "", "", covers(songs), songs, today)
    }

    private fun likesMix(daySeed: Long, today: String): Mix? {
        val liked = OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty().filter { it.title.isNotBlank() }
        if (liked.size < MIN_SONGS) return null
        // Every like, in another order each day: the list people put on when they just want what they like.
        val songs = liked.shuffled(Random(daySeed)).take(LIKES_SIZE)
        return Mix("likes", TopPickKind.LIKES_MIX, "", "", covers(songs), songs, today)
    }

    private fun rediscover(heard: List<TrackEntry>, daySeed: Long, today: String): Mix? {
        val since = System.currentTimeMillis() - REDISCOVER_AFTER_DAYS * DAY_MS
        // Played a lot once, and not for a while; a different handful of them each day.
        val pool = heard.filter { it.plays >= 4 && it.last in 1 until since }.sortedByDescending { it.plays }.take(SIZE * 2)
        if (pool.size < MIN_SONGS) return null
        val songs = pool.shuffled(Random(daySeed)).take(SIZE).map(::toSong)
        return Mix("rediscover", TopPickKind.REDISCOVER, "", "", covers(songs), songs, today)
    }

    private fun oneYearAgo(heard: List<TrackEntry>, today: String): Mix? {
        val then = LocalDate.parse(today).minusYears(1)
        val from = then.minusDays(7).toString()
        val to = then.plusDays(7).toString()
        // What was played the fortnight around this day a year ago, the most played first.
        val played = heard.map { e -> e to e.days.filterKeys { it in from..to }.values.sumOf { it.plays } }
            .filter { (_, plays) -> plays > 0 }
            .sortedByDescending { (_, plays) -> plays }
            .take(SIZE)
            .map { (e, _) -> toSong(e) }
        if (played.size < MIN_SONGS) return null
        return Mix("year", TopPickKind.ONE_YEAR_AGO, then.year.toString(), "", covers(played), played, today)
    }

    private suspend fun deepCuts(ranked: List<TrackEntry>, heardIds: Set<String>, daySeed: Long, today: String): Mix? {
        // The artists played most lately with a page on YouTube Music; one of the first three, a different one daily.
        val artists = ranked.mapNotNull { e -> e.artistId?.takeIf { it.startsWith("UC") }?.let { it to e.artist } }
            .distinctBy { it.first }.take(3)
        if (artists.isEmpty()) return null
        val (artistId, name) = artists[(daySeed % artists.size).toInt()]
        val page = YtMusicRepository.artistLanding(artistId).getOrNull() ?: return null
        val more = page.moreSongsBrowseId?.removePrefix("VL")?.let { YtMusicRepository.playlistAsAudio(it).getOrNull() }.orEmpty()
        // Their songs the listener has not played yet.
        val songs = (page.songs + more).filter { it.videoId.isNotBlank() && it.videoId !in heardIds }
            .distinctBy { it.videoId }.take(SIZE)
        if (songs.size < MIN_SONGS) return null
        val artist = page.name?.takeIf { it.isNotBlank() } ?: name
        val images = (listOfNotNull(page.thumbnailUrl?.takeIf { it.isNotBlank() }) + covers(songs)).distinct().take(4)
        return Mix("deep", TopPickKind.DEEP_CUTS, artist, "", images, songs, today)
    }

    // ---- Apple's genres, kept between launches --------------------------------------------------------------------

    private const val GENRES_FILE = "artist_genres.json"
    @Volatile private var genresSeeded = false

    private fun seedAppleGenres(app: Context) {
        if (genresSeeded) return
        genresSeeded = true
        runCatching {
            val file = File(app.filesDir, GENRES_FILE)
            if (file.exists()) AppleArtistGenres.seed(json.decodeFromString<Map<String, String>>(file.readText()))
        }
    }

    private fun saveAppleGenres(app: Context) {
        runCatching { File(app.filesDir, GENRES_FILE).writeText(json.encodeToString(AppleArtistGenres.snapshot())) }
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private val IGNORED_TAGS = setOf("seen live", "favorites", "favourites", "albums i own", "all", "under 2000 listeners", "spotify")

    private fun isStream(id: String) = id.length == 11 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun covers(songs: List<Song>): List<String> = songs.mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(4)

    private fun toSong(e: TrackEntry) = Song(
        videoId = e.id, title = e.title, artist = e.artist, thumbnailUrl = e.art,
        albumName = e.album, artistId = e.artistId, albumId = e.albumId,
        localUri = e.id.takeIf { it.startsWith("content://") || it.startsWith("file://") },
    )

    // ---- Keeping them ------------------------------------------------------------------------------------------

    @Serializable
    private data class StoredSong(
        val id: String, val title: String, val artist: String, val art: String? = null,
        val album: String? = null, val artistId: String? = null, val albumId: String? = null, val uri: String? = null,
    )

    @Serializable
    private data class StoredMix(
        val id: String, val kind: TopPickKind, val title: String, val subtitle: String,
        val images: List<String>, val songs: List<StoredSong>, val day: String,
    )

    private fun write(file: File, mixes: List<Mix>) {
        val stored = mixes.map { m ->
            StoredMix(
                m.id, m.kind, m.title, m.subtitle, m.images,
                m.songs.map { StoredSong(it.videoId, it.title, it.artist, it.thumbnailUrl, it.albumName, it.artistId, it.albumId, it.localUri) },
                m.day,
            )
        }
        runCatching { file.writeText(json.encodeToString(stored)) }
    }

    private fun read(file: File): List<Mix> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString<List<StoredMix>>(file.readText()).map { m ->
            Mix(
                m.id, m.kind, m.title, m.subtitle, m.images,
                m.songs.map { Song(videoId = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.art, albumName = it.album, artistId = it.artistId, albumId = it.albumId, localUri = it.uri) },
                m.day,
            )
        }
    }.getOrDefault(emptyList())
}
