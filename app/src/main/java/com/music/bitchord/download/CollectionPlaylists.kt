package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import dev.auden.library.io.M3u
import dev.auden.library.io.PlaylistEntry
import dev.auden.library.io.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A `.m3u8` for each album or playlist downloaded whole, in `Playlists/` beside
 * the artist folders, so any other player can open the release as a list.
 *
 * Its lines are relative (`../Artist/Album/03 - Song.flac`), which is what lets
 * the folder be copied to another device or another player's library and still
 * resolve. Rewritten from scratch each time rather than appended to: the file is
 * a view of what is on disk now, and a track deleted since must not stay in it.
 */
internal object CollectionPlaylists {
    private const val TAG = "CollectionPlaylists"
    private const val FOLDER = "Playlists"
    private val lock = Mutex()

    /** Writes the playlist for collection [id] once nothing it asked for is still waiting or running. */
    suspend fun updateWhenSettled(context: Context, id: String) {
        val wanted = Downloads.requested.value[id].orEmpty()
        val busy = wanted.any {
            when (Downloads.active.value[it]) {
                is DownloadState.Queued, is DownloadState.Running -> true
                else -> false
            }
        }
        if (!busy) update(context, id)
    }

    suspend fun update(context: Context, id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val record = Downloads.collections.value[id] ?: return@runCatching
                val saved = Downloads.saved.value
                val meta = Downloads.savedMetadata.value
                val entries = record.videoIds.mapNotNull { videoId ->
                    val uri = saved[videoId]?.let(Uri::parse) ?: return@mapNotNull null
                    if (!DownloadStore.exists(context, uri)) return@mapNotNull null
                    val relative = DownloadStore.relativeName(context, uri) ?: return@mapNotNull null
                    val song = meta[videoId]
                    PlaylistEntry(
                        // Written from the Playlists folder, so up one and across.
                        path = "../$relative",
                        title = song?.title,
                        artist = song?.artist,
                        durationSec = song?.durationText?.let(dev.auden.library.SongIdentity::seconds)?.toDouble(),
                    )
                }
                if (entries.isEmpty()) return@runCatching
                val text = M3u.write(record.title, entries, baseDir = null)
                val name = "$FOLDER/${Paths.safeFileName(record.title)}.m3u8"
                DownloadStore.existing(context, name)?.let { DownloadStore.delete(context, it) }
                val pending = DownloadStore.begin(context, name, "audio/x-mpegurl")
                try {
                    pending.openStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    pending.commit()
                } catch (e: Throwable) {
                    pending.abort()
                    throw e
                }
            }.onFailure { Log.w(TAG, "could not write the playlist file for $id: ${it.message}") }
        }
    }
}
