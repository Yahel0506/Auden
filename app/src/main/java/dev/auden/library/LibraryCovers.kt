package dev.auden.library

import android.content.Context
import android.net.Uri
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives streamed songs their official cover back when the one they were linked to is dead.
 *
 * A song once linked to a file on the device can be left holding that file's MediaStore
 * art address after the file is gone, and nothing answers it. Each such address is opened
 * to confirm it is dead; a dead one is dropped, and the song's real cover is read from
 * YouTube Music itself, as every page that was never saved on the device gets its covers.
 */
object LibraryCovers {
    private const val TAG = "BitChord"
    private const val PARALLEL = 4
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    fun repairAsync(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                repair(app)
            } catch (e: Exception) {
                Log.w(TAG, "cover repair stopped", e)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun repair(context: Context) {
        val db = AudenDb.get(context)
        val candidates = db.tracks().needingCover().filter { track ->
            val url = track.thumbnailUrl
            // A MediaStore address is only replaced once it is confirmed not to open.
            url == null || !url.startsWith("content:") || !opens(context, url)
        }
        if (candidates.isEmpty()) return
        Log.i(TAG, "restoring the official cover of ${candidates.size} songs")
        val gate = Semaphore(PARALLEL)
        val restored = java.util.concurrent.atomic.AtomicInteger()
        candidates.chunked(40).forEach { chunk ->
            coroutineScope {
                chunk.map { track ->
                    async {
                        gate.withPermit {
                            val cover = YtMusicRepository.trackLinks(track.videoId!!).getOrNull()
                                ?.thumbnailUrl?.takeIf { it.startsWith("http") && !it.contains("i.ytimg.com/vi/") }
                            when {
                                cover != null -> { db.tracks().setCover(track.id, cover); restored.incrementAndGet() }
                                // Dead and nothing better yet: no address, rather than one that cannot load.
                                track.thumbnailUrl?.startsWith("content:") == true -> db.tracks().setCover(track.id, null)
                            }
                        }
                    }
                }.awaitAll()
            }
            LocalPlaylists.reload()
        }
        Log.i(TAG, "restored $restored official covers")
    }

    private fun opens(context: Context, url: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(url))?.use { it.read() >= 0 } == true
    }.getOrDefault(false)
}
