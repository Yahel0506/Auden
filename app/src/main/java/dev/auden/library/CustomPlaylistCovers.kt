package dev.auden.library

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Covers the listener chose for playlists kept on this device: one picture per
 * playlist, in the app's own files, named in the playlist's `coverUrl`.
 *
 * In files rather than the cache, since nothing can rebuild a picture the
 * listener picked and framed. Each save takes a new name, so every list
 * showing the old cover loads the new one instead of the image it has cached
 * under the old address.
 */
object CustomPlaylistCovers {
    private const val DIR = "playlist_covers"

    /** The longest side a saved cover keeps: the release page draws it across a phone's width, no wider. */
    const val MAX_SIDE = 1200

    @Volatile private var root: File? = null

    fun init(context: Context) {
        root = File(context.applicationContext.filesDir, DIR)
    }

    /** Whether [url] is a cover saved here, as against the cover of the cloud page a playlist was copied from. */
    fun isCustom(url: String?): Boolean = url != null && url.startsWith("file://") && "/$DIR/" in url

    /**
     * False only for a cover saved here whose file is gone — a library restored from a backup made
     * on another phone names files that never came along, and the automatic cover has to stand in.
     */
    fun usable(url: String): Boolean = !isCustom(url) || File(url.removePrefix("file://")).exists()

    /** Writes [bitmap] as [playlistId]'s cover and returns its address; the playlist's earlier covers go. */
    suspend fun save(context: Context, playlistId: String, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
            root = dir
            val target = File(dir, "${fileKey(playlistId)}-${System.currentTimeMillis()}.jpg")
            val temp = File(dir, target.name + ".part")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            check(temp.renameTo(target)) { "cover not written" }
            dir.listFiles { file -> file.name.startsWith(fileKey(playlistId) + "-") && file != target }
                ?.forEach { it.delete() }
            "file://" + target.absolutePath
        }.getOrNull()
    }

    /** Removes every cover saved for [playlistId] — it was taken off, or the playlist was deleted. */
    fun delete(playlistId: String) {
        val dir = root ?: return
        runCatching {
            dir.listFiles { file -> file.name.startsWith(fileKey(playlistId) + "-") }?.forEach { it.delete() }
        }
    }

    private fun fileKey(playlistId: String): String = playlistId.replace(Regex("[^A-Za-z0-9_]"), "_")
}
