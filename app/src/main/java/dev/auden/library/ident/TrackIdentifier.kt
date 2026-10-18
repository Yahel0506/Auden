package dev.auden.library.ident

import android.content.Context
import android.util.Log
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import dev.auden.library.JobNotification
import dev.auden.library.db.AudenDb
import dev.auden.library.db.IdentSource
import dev.auden.library.db.IdentStatus
import dev.auden.library.db.TrackEntity
import dev.auden.library.links.LocalLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Works out which YouTube Music song each file in the local library is, so the
 * file can be treated as that song's download and the song's details (album,
 * artist page, cover) are known.
 *
 * Cheapest evidence first, stopping at the first that holds:
 *  1. the file name carries the video id ([YoutubeIdInName]) — certain;
 *  2. a YouTube Music search for the file's title and artist, judged by the
 *     same [TrackMatcher] that decides every other cross-catalogue match: a
 *     candidate that really is the recording is applied;
 *  3. a candidate that is plausible but not certain — the artist tag is
 *     missing, or only the first search hit is close — is only *suggested*, and
 *     waits for the listener in the review list.
 *
 * Anything else is marked not found so it is not searched for again every time.
 */
object TrackIdentifier {
    private const val TAG = "TrackIdentifier"
    private const val PARALLELISM = 3
    private const val UNKNOWN_ARTIST = "Unknown Artist"

    data class State(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Looks at every local file not yet looked at. Does nothing while a run is in progress. */
    fun start(context: Context, retryMisses: Boolean = false) {
        if (_state.value.running) return
        _state.value = State(running = true)
        val app = context.applicationContext
        scope.launch {
            val db = AudenDb.get(app)
            val notification = JobNotification(app, NOTIFICATION_ID, app.getString(R.string.identifying_title))
            try {
                if (retryMisses) db.tracks().resetVerdicts()
                val pending = db.tracks().unidentified()
                if (pending.isEmpty()) return@launch
                _state.value = State(true, 0, pending.size)
                notification.indeterminate(app.getString(R.string.identifying_starting))
                val gate = Semaphore(PARALLELISM)
                val done = AtomicInteger()
                val matched = AtomicInteger()
                val review = AtomicInteger()
                coroutineScope {
                    pending.map { track ->
                        async {
                            gate.withPermit {
                                when (identify(db, track)) {
                                    IdentStatus.MATCHED -> matched.incrementAndGet()
                                    IdentStatus.REVIEW -> review.incrementAndGet()
                                    else -> Unit
                                }
                                val n = done.incrementAndGet()
                                _state.value = State(true, n, pending.size)
                                notification.progress(app.getString(R.string.identifying_progress, n, pending.size), n, pending.size)
                            }
                        }
                    }.awaitAll()
                }
                notification.finish(
                    app.getString(R.string.identifying_done_title),
                    app.getString(R.string.identifying_done_text, matched.get(), review.get(), pending.size - matched.get() - review.get()),
                )
                LocalLinks.rebuild(app)
            } catch (e: Exception) {
                Log.w(TAG, "identification stopped", e)
                notification.finish(app.getString(R.string.identifying_title), app.getString(R.string.save_failed_text))
            } finally {
                _state.value = State()
            }
        }
    }

    /** The listener agreed with a suggestion. */
    fun accept(context: Context, trackId: Long) {
        val app = context.applicationContext
        scope.launch {
            val db = AudenDb.get(app)
            val track = db.tracks().byId(trackId) ?: return@launch
            val id = track.suggestedVideoId ?: return@launch
            db.tracks().update(
                track.copy(
                    videoId = id, identStatus = IdentStatus.MATCHED, identSource = IdentSource.MANUAL,
                    suggestedVideoId = null, suggestedTitle = null, suggestedArtist = null,
                    identifiedAt = System.currentTimeMillis(),
                ),
            )
            LocalLinks.rebuild(app)
        }
    }

    fun acceptAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val db = AudenDb.get(app)
            AudenDb.get(app).tracks().reviewNow().forEach { accept(app, it.id) }
        }
    }

    /** The listener said no: remembered, so the same wrong suggestion does not come back. */
    fun reject(context: Context, trackId: Long) {
        val app = context.applicationContext
        scope.launch {
            val db = AudenDb.get(app)
            val track = db.tracks().byId(trackId) ?: return@launch
            db.tracks().update(
                track.copy(
                    identStatus = IdentStatus.REJECTED, suggestedVideoId = null, suggestedTitle = null,
                    suggestedArtist = null, identifiedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun identify(db: AudenDb, track: TrackEntity): String {
        val now = System.currentTimeMillis()
        // 1. The id is in the name.
        val named = track.path?.let { YoutubeIdInName.extract(it.substringAfterLast('/')) }
        if (named != null) {
            db.tracks().update(track.copy(videoId = named, identStatus = IdentStatus.MATCHED, identSource = IdentSource.FILE_NAME, identifiedAt = now))
            return IdentStatus.MATCHED
        }
        // 2 and 3. Ask YouTube Music.
        val artistKnown = track.artist.isNotBlank() && track.artist != UNKNOWN_ARTIST
        val target = TrackMatcher.Target(
            title = track.title,
            artist = if (artistKnown) track.artist else "",
            durationSec = track.durationMs?.div(1000)?.toInt() ?: TrackMatcher.secondsOf(track.durationText),
            album = track.album,
        )
        val candidates = linkedMapOf<String, Song>()
        for (query in TrackMatcher.queries(target).take(2)) {
            val found = runCatching { YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() }.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
            found.forEach { candidates.putIfAbsent(it.videoId, it) }
            // The first query is the precise one; a confident answer to it needs no second.
            if (artistKnown && TrackMatcher.best(candidates.values.toList(), target) != null) break
        }
        val list = candidates.values.toList()
        val certain = if (artistKnown) TrackMatcher.best(list, target) else null
        return when {
            certain != null -> {
                db.tracks().update(
                    track.copy(
                        videoId = certain.videoId, identStatus = IdentStatus.MATCHED, identSource = IdentSource.SEARCH,
                        album = track.album ?: certain.albumName, artistId = track.artistId ?: certain.artistId,
                        albumId = track.albumId ?: certain.albumId, identifiedAt = now,
                    ),
                )
                IdentStatus.MATCHED
            }
            list.isNotEmpty() -> {
                // Without an artist the "best" has nothing to be checked against, so it is only offered.
                val guess = TrackMatcher.best(list, target.copy(artist = "")) ?: list.first()
                db.tracks().update(
                    track.copy(
                        identStatus = IdentStatus.REVIEW, suggestedVideoId = guess.videoId,
                        suggestedTitle = guess.title, suggestedArtist = guess.artist, identifiedAt = now,
                    ),
                )
                IdentStatus.REVIEW
            }
            else -> {
                db.tracks().update(track.copy(identStatus = IdentStatus.NOT_FOUND, identifiedAt = now))
                IdentStatus.NOT_FOUND
            }
        }
    }

    private const val NOTIFICATION_ID = 0x9200
}
