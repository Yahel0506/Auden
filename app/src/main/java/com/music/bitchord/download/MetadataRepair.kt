package com.music.bitchord.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.sources.TrackMatcher
import dev.auden.library.lyrics.LyricsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Finds the tags a downloaded or device file is missing — title, artist, album
 * and cover — and writes them in from the song's own record, overwriting the
 * file with the same bytes plus what was missing.
 *
 * Lyrics are not part of the check: they have their own action. They are carried
 * across the rewrite, from the stored lyrics where there are some and from the
 * file's own tag where there are not, so repairing a file never costs it its
 * words.
 *
 * What is kept is never replaced. A field the file already has stays as it is;
 * only a missing one is filled. A song is only repaired when its recording is
 * certain: a download is looked up by its id, and a device file is matched with
 * the same [TrackMatcher] rule the library uses, with no guessing.
 */
object MetadataRepair {

    private const val TAG = "BitChord"

    /** Longest side of a cover that came out of a file, once it has to be re-encoded. */
    private const val JPEG_QUALITY = 92

    /** Which tags a file is missing. Lyrics are deliberately not one of them. */
    enum class Gap { TITLE, ARTIST, ALBUM, COVER }

    /** What a file's tags say, as far as the platform reads them. */
    internal data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val track: Int?,
        val year: Int?,
        /** The cover as the file carries it, or null when it has none. */
        val picture: ByteArray?,
    )

    enum class Outcome { COMPLETE, REPAIRED, UNAVAILABLE, NOT_A_FILE }

    /** What the repair is doing to the song it is on, for the progress dialog. */
    enum class Step { READING, LOOKING_UP, DOWNLOADING_COVER, WRITING, CHECKING }

    /** Why a song could not be repaired. */
    enum class Reason { UNREADABLE, NO_MATCH, NO_COVER, WRITE_FAILED, UNSUPPORTED }

    /** One song's result: what it lacked, what was filled in and, if not all, why. */
    data class SongResult(
        val title: String,
        val artist: String,
        val outcome: Outcome,
        val gaps: Set<Gap> = emptySet(),
        val filled: Set<Gap> = emptySet(),
        val reason: Reason? = null,
    )

    data class Report(
        val repaired: Int = 0,
        val complete: Int = 0,
        val unavailable: Int = 0,
        val covers: Int = 0,
        val titles: Int = 0,
        val artists: Int = 0,
        val albums: Int = 0,
    ) {
        operator fun plus(outcome: Outcome): Report = when (outcome) {
            Outcome.REPAIRED -> copy(repaired = repaired + 1)
            Outcome.COMPLETE -> copy(complete = complete + 1)
            Outcome.UNAVAILABLE -> copy(unavailable = unavailable + 1)
            Outcome.NOT_A_FILE -> this
        }

        operator fun plus(result: SongResult): Report = (this + result.outcome).copy(
            covers = covers + if (Gap.COVER in result.filled) 1 else 0,
            titles = titles + if (Gap.TITLE in result.filled) 1 else 0,
            artists = artists + if (Gap.ARTIST in result.filled) 1 else 0,
            albums = albums + if (Gap.ALBUM in result.filled) 1 else 0,
        )

        val checked: Int get() = repaired + complete + unavailable
    }

    /** The live state of a run, read by the dialog. Null when none is on screen. */
    data class Session(
        val total: Int,
        val done: Int = 0,
        val current: Song? = null,
        val step: Step? = null,
        val results: List<SongResult> = emptyList(),
        val report: Report = Report(),
        val finished: Boolean = false,
        val cancelled: Boolean = false,
        /** Accepted suggestions waiting for the listener to say whether to rewrite them. */
        val offer: List<Accepted>? = null,
    )

    /** A suggestion the listener accepted: the device file, and the song it was matched to. */
    data class Accepted(val song: Song, val videoId: String, val title: String?, val artist: String?)

    private val queued = mutableListOf<Accepted>()

    /** Remembers an accepted suggestion until the review closes and the offer can be made. */
    fun queueAccepted(item: Accepted) {
        synchronized(queued) { queued.removeAll { it.song.localUri == item.song.localUri }; queued += item }
    }

    /** Asks whether to rewrite the files of everything accepted since the last offer. */
    fun offerQueued() {
        if (_session.value != null) return
        val items = synchronized(queued) { queued.toList().also { queued.clear() } }
        if (items.isEmpty()) return
        _session.value = Session(total = items.size, offer = items)
    }

    /** The listener agreed: rewrites the offered files from the songs they were matched to. */
    fun startOffered(context: Context) {
        val offered = _session.value?.offer ?: return
        val app = context.applicationContext
        launchRun(offered.map { item -> item.song to suspend { onStep: (Step) -> Unit -> repairAccepted(app, item, onStep) } })
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private var job: Job? = null

    fun dismiss() {
        job?.cancel()
        job = null
        _session.value = null
    }

    /** Stops a run in progress and shows what it got through. */
    fun cancel() {
        val running = job ?: return
        running.cancel()
        _session.update { it?.copy(finished = true, cancelled = true, current = null, step = null) }
    }

    /** The gaps in [tags], for a pure check. */
    internal fun gapsOf(tags: Tags): Set<Gap> = buildSet {
        if (tags.title.isNullOrBlank()) add(Gap.TITLE)
        if (tags.artist.isNullOrBlank()) add(Gap.ARTIST)
        if (tags.album.isNullOrBlank()) add(Gap.ALBUM)
        if (tags.picture == null || tags.picture.isEmpty()) add(Gap.COVER)
    }

    /**
     * Runs the repair for [songs] in the background and publishes its progress
     * through [session]; the dialog shows each step and, at the end, the summary.
     * A song that only streams is skipped without a word.
     */
    fun start(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        launchRun(songs.distinctBy { it.videoId + "|" + it.localUri }.map { song ->
            song to suspend { onStep: (Step) -> Unit -> repairSong(app, song, onStep) }
        })
    }

    private fun launchRun(entries: List<Pair<Song, suspend ((Step) -> Unit) -> SongResult>>) {
        if (job?.isActive == true) return
        _session.value = Session(total = entries.size)
        job = scope.launch {
            var report = Report()
            val results = mutableListOf<SongResult>()
            var done = 0
            for ((song, run) in entries) {
                if (!isActive) return@launch
                _session.update { it?.copy(current = song, step = Step.READING) }
                val result = run { step -> _session.update { it?.copy(step = step) } }
                done++
                if (result.outcome != Outcome.NOT_A_FILE) {
                    report += result
                    results += result
                }
                _session.update { it?.copy(done = done, results = results.toList(), report = report) }
            }
            _session.update { it?.copy(finished = true, current = null, step = null) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val COVER_PREFS = "metadata_repair"

    private fun coverMarked(context: Context, uri: Uri): Boolean =
        context.getSharedPreferences(COVER_PREFS, Context.MODE_PRIVATE).getBoolean(uri.toString(), false)

    private fun markCover(context: Context, uri: Uri) {
        context.getSharedPreferences(COVER_PREFS, Context.MODE_PRIVATE).edit().putBoolean(uri.toString(), true).apply()
    }

    /** Formats whose cover the platform's reader cannot see, so the file cannot answer "has a cover?". */
    private fun coverUnreadable(extension: String) = extension == "webm"

    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** The result of repairing one song's file; [Outcome.NOT_A_FILE] for one that only streams. */
    suspend fun repairSong(context: Context, song: Song, onStep: (Step) -> Unit = {}): SongResult =
        withContext(Dispatchers.IO) {
            val saved = Downloads.savedMetadata.value[song.videoId]
            val downloaded = saved != null
            fun result(outcome: Outcome, gaps: Set<Gap> = emptySet(), filled: Set<Gap> = emptySet(), reason: Reason? = null): SongResult {
                if (outcome == Outcome.UNAVAILABLE) Log.w(TAG, "repair of '${song.title}' left as it was: $reason, gaps=$gaps")
                return SongResult(song.title, song.artist, outcome, gaps, filled, reason)
            }

            val uri: Uri = (if (downloaded) Downloads.savedUri(context, song.videoId) else null)
                ?: song.localUri?.let(Uri::parse)
                ?: return@withContext result(Outcome.NOT_A_FILE)
            val name = DownloadStore.relativeName(context, uri) ?: uri.lastPathSegment.orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (!MediaTagger.carriesTags(extension)) return@withContext result(Outcome.UNAVAILABLE, reason = Reason.UNSUPPORTED)

            onStep(Step.READING)
            val before = readTags(context, uri) ?: return@withContext result(Outcome.UNAVAILABLE, reason = Reason.UNREADABLE)
            val gaps = gapsOf(before).toMutableSet()
            // The reader cannot see a cover in this container, so one this run
            // already wrote is remembered instead of rewritten every time.
            if (coverUnreadable(extension) && Gap.COVER in gaps && coverMarked(context, uri)) gaps -= Gap.COVER
            if (gaps.isEmpty()) return@withContext result(Outcome.COMPLETE)

            onStep(Step.LOOKING_UP)
            val base = song.copy(
                title = song.title.ifBlank { saved?.title.orEmpty() },
                artist = song.artist.ifBlank { saved?.artist.orEmpty() },
                thumbnailUrl = song.thumbnailUrl ?: saved?.thumbnailUrl,
                albumName = song.albumName ?: saved?.albumName,
            )
            val found = recordingFor(base, before, downloaded)
            val track = base.copy(
                title = base.title.ifBlank { found?.title.orEmpty() },
                artist = base.artist.ifBlank { found?.artist.orEmpty() },
                albumName = base.albumName?.takeIf { it.isNotBlank() } ?: found?.albumName,
            )

            // No certain match on YouTube Music: Apple's catalogue may still know the song.
            var itunes: ItunesLookup.Hit? = null
            val needsAlbum = Gap.ALBUM in gaps && track.albumName.isNullOrBlank()
            if ((needsAlbum || Gap.COVER in gaps) && (found == null || found.thumbnailUrl.isNullOrBlank())) {
                itunes = ItunesLookup.find(track.title.ifBlank { before.title.orEmpty() }, track.artist.ifBlank { before.artist.orEmpty() })
            }
            val trackWithAlbum = if (needsAlbum && itunes?.album != null) track.copy(albumName = itunes.album) else track

            var cover: MediaTagger.Artwork? = null
            if (Gap.COVER in gaps) {
                onStep(Step.DOWNLOADING_COVER)
                // A device file's own thumbnail is a content address, not something to download.
                val urls = listOfNotNull(base.thumbnailUrl, found?.thumbnailUrl, itunes?.artworkUrl).filter { it.startsWith("http") }
                cover = coverFor(song.videoId, urls)
            }
            val filled = buildSet {
                if (Gap.TITLE in gaps && track.title.isNotBlank()) add(Gap.TITLE)
                if (Gap.ARTIST in gaps && track.artist.isNotBlank()) add(Gap.ARTIST)
                if (Gap.ALBUM in gaps && !trackWithAlbum.albumName.isNullOrBlank()) add(Gap.ALBUM)
                if (Gap.COVER in gaps && cover != null) add(Gap.COVER)
            }
            if (filled.isEmpty()) {
                // A single has no album to find; that is not a failure.
                if (gaps == setOf(Gap.ALBUM)) return@withContext result(Outcome.COMPLETE, gaps)
                val reason = if (gaps.any { it != Gap.COVER && it != Gap.ALBUM } || found == null && Gap.COVER !in gaps) {
                    Reason.NO_MATCH
                } else if (Gap.COVER in gaps) Reason.NO_COVER else Reason.NO_MATCH
                return@withContext result(Outcome.UNAVAILABLE, gaps, reason = reason)
            }

            // Written over the file, so everything it carries has to be handed back
            // in: the file's own values win, the new ones fill what was missing, and
            // the cover and lyrics are passed through rather than dropped.
            onStep(Step.WRITING)
            val merged = trackWithAlbum.copy(
                title = before.title?.takeIf { it.isNotBlank() } ?: track.title,
                artist = before.artist?.takeIf { it.isNotBlank() } ?: track.artist,
                albumName = before.album?.takeIf { it.isNotBlank() } ?: track.albumName,
            )
            val wrote = MediaTagger.embed(
                context = context,
                uri = uri,
                track = merged,
                extension = extension,
                lyrics = lyricsFor(context, song, uri, downloaded),
                artwork = cover ?: before.picture?.let(::asArtwork),
                extras = extrasFor(song, before, downloaded),
            )
            if (!wrote) return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)

            // Checked rather than trusted. What the reader cannot see in this
            // container is taken from the write itself.
            onStep(Step.CHECKING)
            val after = readTags(context, uri) ?: return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)
            val stillMissing = gapsOf(after)
            val confirmed = filled.filterTo(mutableSetOf()) { gap ->
                if (gap == Gap.COVER && coverUnreadable(extension)) true else gap !in stillMissing
            }
            if (Gap.COVER in confirmed && coverUnreadable(extension)) markCover(context, uri)
            if (confirmed.isEmpty()) return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)
            Log.d(TAG, "repaired ${song.videoId}: $confirmed")
            result(Outcome.REPAIRED, gaps, confirmed)
        }

    /**
     * Rewrites the tags of a device file to those of the song its accepted
     * suggestion points at: title, artist and album replace what the file said,
     * and a cover is added where it has none. Unlike [repairSong] this does
     * overwrite — the listener has said the file's own tags are the inexact ones.
     */
    suspend fun repairAccepted(context: Context, item: Accepted, onStep: (Step) -> Unit = {}): SongResult =
        withContext(Dispatchers.IO) {
            val song = item.song
            fun result(outcome: Outcome, gaps: Set<Gap> = emptySet(), filled: Set<Gap> = emptySet(), reason: Reason? = null) =
                SongResult(song.title, song.artist, outcome, gaps, filled, reason)

            val uri = song.localUri?.let(Uri::parse) ?: return@withContext result(Outcome.NOT_A_FILE)
            val name = DownloadStore.relativeName(context, uri) ?: uri.lastPathSegment.orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (!MediaTagger.carriesTags(extension)) return@withContext result(Outcome.UNAVAILABLE, reason = Reason.UNSUPPORTED)

            onStep(Step.READING)
            val before = readTags(context, uri) ?: return@withContext result(Outcome.UNAVAILABLE, reason = Reason.UNREADABLE)

            onStep(Step.LOOKING_UP)
            val record = recordOf(item, before)
            val title = (record?.title ?: item.title)?.trim().orEmpty()
            val artist = (record?.artist ?: item.artist)?.trim().orEmpty()
            val album = record?.albumName?.trim()?.takeIf { it.isNotBlank() }
            if (title.isBlank() && artist.isBlank()) return@withContext result(Outcome.UNAVAILABLE, reason = Reason.NO_MATCH)

            fun differs(new: String?, old: String?) = !new.isNullOrBlank() && !new.equals(old?.trim(), ignoreCase = false)
            val gaps = buildSet {
                if (differs(title, before.title)) add(Gap.TITLE)
                if (differs(artist, before.artist)) add(Gap.ARTIST)
                if (differs(album, before.album)) add(Gap.ALBUM)
                if (before.picture == null) add(Gap.COVER)
            }
            if (gaps.isEmpty()) return@withContext result(Outcome.COMPLETE)

            var cover: MediaTagger.Artwork? = null
            if (Gap.COVER in gaps) {
                onStep(Step.DOWNLOADING_COVER)
                cover = coverFor(item.videoId, listOfNotNull(record?.thumbnailUrl))
            }
            val wanted = gaps.filterTo(mutableSetOf()) { it != Gap.COVER || cover != null }
            if (wanted.isEmpty()) return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.NO_COVER)

            onStep(Step.WRITING)
            val track = song.copy(
                videoId = item.videoId,
                title = title.ifBlank { before.title.orEmpty() },
                artist = artist.ifBlank { before.artist.orEmpty() },
                albumName = album ?: before.album,
            )
            val wrote = MediaTagger.embed(
                context = context,
                uri = uri,
                track = track,
                extension = extension,
                lyrics = lyricsFor(context, song, uri, false),
                artwork = cover ?: before.picture?.let(::asArtwork),
                extras = extrasFor(song, before, false),
            )
            if (!wrote) return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)

            onStep(Step.CHECKING)
            val after = readTags(context, uri) ?: return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)
            val confirmed = wanted.filterTo(mutableSetOf()) { gap ->
                when (gap) {
                    Gap.TITLE -> after.title == track.title
                    Gap.ARTIST -> after.artist == track.artist
                    Gap.ALBUM -> after.album == track.albumName
                    Gap.COVER -> coverUnreadable(extension) || after.picture != null
                }
            }
            if (confirmed.isEmpty()) return@withContext result(Outcome.UNAVAILABLE, gaps, reason = Reason.WRITE_FAILED)
            result(Outcome.REPAIRED, gaps, confirmed)
        }

    /** The catalogue record the accepted suggestion names, found by its id among its own search results. */
    private suspend fun recordOf(item: Accepted, tags: Tags): Song? {
        val target = TrackMatcher.Target(
            title = item.title ?: tags.title.orEmpty(),
            artist = item.artist ?: tags.artist.orEmpty(),
            durationSec = TrackMatcher.secondsOf(item.song.durationText),
            album = tags.album,
        )
        if (target.title.isBlank()) return null
        val queries = if (target.artist.isBlank()) listOf(target.title) else TrackMatcher.queries(target).take(3)
        for (query in queries) {
            runCatching { YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() }.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.firstOrNull { it.song.videoId == item.videoId }
                ?.let { return it.song }
        }
        return null
    }

    /**
     * A cover from the first of [urls] that still answers, then from YouTube's own
     * thumbnail of [videoId] cropped to its middle square — a stale or missing
     * address no longer leaves a song without one.
     */
    private fun coverFor(videoId: String, urls: List<String>): MediaTagger.Artwork? {
        for (url in urls.distinct()) {
            val sized = url.artworkAt(1200) ?: url
            MediaTagger.coverFromUrl(sized, videoId)?.let { return it }
            if (sized != url) MediaTagger.coverFromUrl(url, videoId)?.let { return it }
        }
        if (YOUTUBE_ID.matches(videoId)) {
            for (name in listOf("maxresdefault", "hqdefault")) {
                MediaTagger.coverFromUrl("https://i.ytimg.com/vi/$videoId/$name.jpg", videoId, cropSquare = true)
                    ?.let { return it }
            }
        }
        return null
    }

    /** The tags [uri] carries, or null when the platform cannot read the file. */
    internal fun readTags(context: Context, uri: Uri): Tags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim(),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim(),
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.trim()?.toIntOrNull(),
                // A cover that does not decode is as good as none: it is replaced.
                picture = retriever.embeddedPicture?.takeIf { decodes(it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read tags of $uri: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodes(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /**
     * The catalogue record of the song, or null when none is certain. A download
     * is looked up by its id among the results for its own title and artist, then
     * by [TrackMatcher]; a device file is taken only on a [TrackMatcher] match.
     */
    private suspend fun recordingFor(song: Song, tags: Tags, downloaded: Boolean): Song? {
        val target = TrackMatcher.Target(
            title = tags.title?.takeIf { it.isNotBlank() } ?: song.title,
            artist = tags.artist?.takeIf { it.isNotBlank() } ?: song.artist,
            durationSec = TrackMatcher.secondsOf(song.durationText),
            album = tags.album?.takeIf { it.isNotBlank() } ?: song.albumName,
        )
        if (target.title.isBlank()) return null
        val candidates = linkedMapOf<String, Song>()
        val queries = if (target.artist.isBlank()) listOf(target.title) else TrackMatcher.queries(target).take(3)
        for (query in queries) {
            runCatching { YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() }.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.forEach { candidates.putIfAbsent(it.song.videoId, it.song) }
            if (downloaded && candidates.containsKey(song.videoId)) break
            if (TrackMatcher.best(candidates.values.toList(), target) != null) break
        }
        if (downloaded) candidates[song.videoId]?.let { return it }
        if (target.artist.isBlank()) return null
        return TrackMatcher.best(candidates.values.toList(), target)
    }

    /**
     * The lyrics to hand back into the rewrite. A download's stored lyrics are the
     * fullest copy, including the word-timed form; a file without any stored copy
     * keeps the lines its own tag holds.
     */
    private suspend fun lyricsFor(context: Context, song: Song, uri: Uri, downloaded: Boolean): LyricsTag.Embeddable? {
        if (downloaded) {
            LyricsStore.active(context, song.videoId)?.result?.artifact?.let { artifact ->
                return LyricsTag.Embeddable(
                    plain = artifact.lines.toLrc(),
                    enhanced = artifact.lines.toEnhancedLrc().takeIf { it.isNotBlank() },
                )
            }
        }
        val lines = EmbeddedLyrics.forUri(context, uri.toString()) ?: return null
        return LyricsTag.Embeddable(plain = lines.toLrc(), enhanced = null)
    }

    /** The positional and descriptive tags a rewrite would otherwise drop. */
    private fun extrasFor(song: Song, tags: Tags, downloaded: Boolean): TagExtras = TagExtras(
        albumArtist = tags.albumArtist?.takeIf { it.isNotBlank() },
        track = tags.track,
        year = tags.year,
        comment = "https://music.youtube.com/watch?v=${song.videoId}"
            .takeIf { downloaded && !song.videoId.startsWith("content:") },
    )

    /** A file's own cover, handed back as the JPEG the taggers expect. */
    private fun asArtwork(picture: ByteArray): MediaTagger.Artwork {
        val isPng = picture.size > 3 && picture[0] == 0x89.toByte() && picture[1] == 'P'.code.toByte()
        if (!isPng) return MediaTagger.Artwork(picture, "image/jpeg")
        val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size) ?: return MediaTagger.Artwork(picture, "image/png")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return MediaTagger.Artwork(out.toByteArray(), "image/jpeg")
    }
}
