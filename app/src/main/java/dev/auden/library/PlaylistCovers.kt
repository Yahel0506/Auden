package dev.auden.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.spotify.LocalPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The cover of a device playlist that has none of its own: the artwork of its
 * first songs laid out as a two-by-two mosaic, the way music apps draw an
 * automatic playlist cover.
 *
 * Built once per distinct set of covers and kept in the cache directory, named
 * after a hash of the covers it was made from — so editing the playlist changes
 * the name and the old image is simply never asked for again.
 */
object PlaylistCovers {
    private const val TILE = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _revision = MutableStateFlow(0)

    /** Bumps whenever a mosaic finishes, so the lists showing covers can ask again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** The covers a mosaic for [playlist] is made of: up to four distinct ones, or none when fewer than two exist. */
    internal fun sourcesOf(playlist: LocalPlaylist): List<String> =
        playlist.songs.mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(4).takeIf { it.size >= 2 } ?: emptyList()

    /**
     * The mosaic's file uri if it exists, else null — and it starts being built, to be there next time
     * (see [revision]). Callers fall back to the first song's cover meanwhile.
     */
    fun coverFor(context: Context, playlist: LocalPlaylist): String? {
        val sources = sourcesOf(playlist)
        if (sources.isEmpty()) return null
        val file = File(File(context.cacheDir, "mosaics"), hash(sources) + ".jpg")
        if (file.exists()) return "file://" + file.absolutePath
        if (inFlight.add(file.name)) {
            val app = context.applicationContext
            scope.launch {
                runCatching { build(app, sources, file) }
                inFlight.remove(file.name)
                if (file.exists()) _revision.value += 1
            }
        }
        return null
    }

    private suspend fun build(context: Context, sources: List<String>, target: File) {
        val loader = SingletonImageLoader.get(context)
        val tiles = sources.mapNotNull { url ->
            val result = loader.execute(ImageRequest.Builder(context).data(url).size(TILE, TILE).allowHardware(false).build())
            (result as? SuccessResult)?.image?.toBitmap()
        }
        if (tiles.size < 2) return
        val out = Bitmap.createBitmap(TILE * 2, TILE * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (slot in 0 until 4) {
            // Fewer than four covers repeat from the start, so the grid is always full.
            val tile = tiles[slot % tiles.size]
            val side = minOf(tile.width, tile.height)
            val src = Rect((tile.width - side) / 2, (tile.height - side) / 2, (tile.width + side) / 2, (tile.height + side) / 2)
            val x = (slot % 2) * TILE
            val y = (slot / 2) * TILE
            canvas.drawBitmap(tile, src, Rect(x, y, x + TILE, y + TILE), null)
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        temp.renameTo(target)
    }

    private fun hash(sources: List<String>): String =
        MessageDigest.getInstance("SHA-1").digest(sources.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
}
