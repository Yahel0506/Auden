package dev.auden.library

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
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
 * Copies cloud playlists and albums (YouTube Music, Spotify) into the device
 * library, reporting progress on a system notification as it goes.
 *
 * Runs in its own scope rather than a screen's or a view model's, so leaving
 * the page it was started from does not abandon it, and says how it is doing
 * through [saving] (for the in-app buttons) and the notification (for
 * everything else) instead of a transient message that has to be read in time.
 */
object CloudSaver {
    private const val MATCH_PARALLELISM = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _saving = MutableStateFlow<Set<String>>(emptySet())

    /** Source ids being saved right now. */
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    sealed interface Result {
        data class Saved(val name: String, val added: Int, val created: Boolean, val unmatched: Int) : Result
        data object Failed : Result
    }

    /** The key a cloud page is saved under: its browse id without YouTube's `VL` prefix. */
    fun sourceId(browseId: String) = browseId.removePrefix("VL")

    fun save(context: Context, browseId: String, title: String, thumbnailUrl: String?, onDone: (Result) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch { onDone(saveNow(app, browseId, title, thumbnailUrl)) }
    }

    /**
     * Brings every library copy of a Spotify playlist up to date, one after another so a long list of them does
     * not hit Spotify all at once. Returns how many were synced.
     */
    suspend fun syncSpotifyCopies(context: Context): Int {
        val copies = LocalPlaylists.playlists.value.filter { it.sourceId?.startsWith(SPOTIFY_PAGE_PREFIX) == true }
        var done = 0
        for (copy in copies) {
            if (saveNow(context.applicationContext, copy.sourceId!!, copy.title, null) is Result.Saved) done++
        }
        return done
    }

    private suspend fun saveNow(app: Context, browseId: String, title: String, thumbnailUrl: String?): Result {
        val source = sourceId(browseId)
        if (source in _saving.value) return Result.Failed
        _saving.value = _saving.value + source
        val notifier = Notifier(app, source, title)
        notifier.reading()
        val result = runCatching { run(app, browseId, source, title, thumbnailUrl, notifier) }
            .getOrElse { Result.Failed }
        when (result) {
            is Result.Saved -> notifier.done(result)
            Result.Failed -> notifier.failed()
        }
        _saving.value = _saving.value - source
        return result
    }

    private suspend fun run(context: Context, browseId: String, source: String, title: String, thumbnailUrl: String?, notifier: Notifier): Result {
        var unmatched = 0
        val songs: List<Song> = if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            val tracks = SpotifyLibrary.tracks(browseId.removePrefix(SPOTIFY_PAGE_PREFIX)) { }
            val gate = Semaphore(MATCH_PARALLELISM)
            val done = AtomicInteger()
            val matched = coroutineScope {
                tracks.map { track ->
                    async {
                        gate.withPermit {
                            val found = runCatching { dev.auden.library.spotify.SpotifyMatches.resolve(context, track) }.getOrNull()
                            notifier.matching(done.incrementAndGet(), tracks.size)
                            found
                        }
                    }
                }.awaitAll()
            }
            unmatched = matched.count { it == null }
            matched.filterNotNull()
        } else {
            val isAlbum = browseId.startsWith("MPREb") || browseId.startsWith("OLAK")
            YtMusicRepository.allSongs(browseId).getOrThrow().map { song ->
                song.copy(
                    albumName = song.albumName ?: title.takeIf { isAlbum },
                    thumbnailUrl = song.thumbnailUrl ?: thumbnailUrl,
                )
            }
        }
        require(songs.isNotEmpty()) { "nothing to save" }
        notifier.storing(songs.size)
        val saved = LocalPlaylists.saveFromSource(source, title, songs)
        return Result.Saved(saved.playlist.title, saved.added, saved.created, unmatched)
    }

    /** One save's notification, worded for each stage of it. */
    private class Notifier(private val context: Context, source: String, private val title: String) {
        private val job = JobNotification(context, 0x9100 + (source.hashCode() and 0xFFF), context.getString(R.string.saving_title, title))

        fun reading() = job.indeterminate(context.getString(R.string.saving_reading))

        fun matching(done: Int, total: Int) = job.progress(context.getString(R.string.saving_matching, done, total), done, total)

        fun storing(count: Int) = job.indeterminate(context.getString(R.string.saving_storing, count))

        fun done(result: Result.Saved) {
            val text = when {
                result.unmatched > 0 -> context.getString(R.string.saved_text_unmatched, result.added, result.unmatched)
                result.created -> context.getString(R.string.saved_text, result.added)
                else -> context.getString(R.string.synced_text, result.added)
            }
            job.finish(context.getString(R.string.saved_title, result.name), text)
        }

        fun failed() = job.finish(context.getString(R.string.save_failed_title, title), context.getString(R.string.save_failed_text))
    }
}
