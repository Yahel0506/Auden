package dev.auden.library.lyrics

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.model.Song
import dev.auden.library.JobNotification
import dev.auden.library.SongIdentity
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds lyrics for a whole album, playlist or folder in the background and keeps them, so they are there
 * offline later. Songs that already have a stored version are left alone.
 *
 * Reports on a notification as it goes; a batch is long enough that nobody should have to watch the screen.
 */
object LyricsBatch {
    private const val PARALLELISM = 3
    private const val PER_SONG_MS = 40_000L
    private const val NOTIFICATION_ID = 0x9300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(context: Context, songs: List<Song>, title: String) {
        if (_running.value || songs.isEmpty()) return
        _running.value = true
        val app = context.applicationContext
        scope.launch {
            val notification = JobNotification(app, NOTIFICATION_ID, app.getString(R.string.lyrics_batch_title, title))
            try {
                val sources = AppSettings.lyricsSources.value
                val order = AppSettings.lyricsSourceOrder.value
                val todo = songs.distinctBy { LyricsStore.key(it) }
                    .filter { SongIdentity.seconds(it.durationText) != null && LyricsStore.variants(app, LyricsStore.key(it)).isEmpty() }
                if (todo.isEmpty()) {
                    notification.finish(app.getString(R.string.lyrics_batch_title, title), app.getString(R.string.lyrics_batch_nothing))
                    return@launch
                }
                notification.indeterminate(app.getString(R.string.identifying_starting))
                val gate = Semaphore(PARALLELISM)
                val done = AtomicInteger()
                val found = AtomicInteger()
                coroutineScope {
                    todo.map { song ->
                        async {
                            gate.withPermit {
                                val result = withTimeoutOrNull(PER_SONG_MS) {
                                    runCatching {
                                        LyricsRepository.lyrics(
                                            videoId = song.videoId, title = song.title, artist = song.artist,
                                            durationMs = (SongIdentity.seconds(song.durationText) ?: 0) * 1000L,
                                            album = song.albumName, sources = sources, order = order,
                                            prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value,
                                        )
                                    }.getOrNull()
                                }
                                result?.artifact?.let {
                                    LyricsStore.save(app, LyricsStore.key(song), it, makeActive = true)
                                    found.incrementAndGet()
                                }
                                val n = done.incrementAndGet()
                                notification.progress(app.getString(R.string.identifying_progress, n, todo.size), n, todo.size)
                            }
                        }
                    }.awaitAll()
                }
                notification.finish(
                    app.getString(R.string.lyrics_batch_done_title, title),
                    app.getString(R.string.lyrics_batch_done_text, found.get(), todo.size - found.get()),
                )
            } finally {
                _running.value = false
            }
        }
    }
}
