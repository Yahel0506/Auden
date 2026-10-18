package dev.auden.library.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadLayout
import com.music.bitchord.download.DownloadStore
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.LyricsSidecarStore
import com.music.bitchord.download.SavedSongMetadata
import dev.auden.library.SongIdentity
import dev.auden.library.io.PlaylistMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What is in the download folder and whether it is where it should be.
 *
 * Three questions, answered from the app's record of its downloads and a look
 * at the folder itself:
 *  - **Misplaced**: a download whose file is not at the path the current folder
 *    layout would give it — everything saved before the layout existed, or
 *    under a different template.
 *  - **Orphans**: audio files in the download folder that no record points to,
 *    left behind by an interrupted download or a reinstall.
 *  - **Duplicates**: the same recording downloaded more than once.
 *
 * Moving is reversible: every move is written to an undo log first.
 */
object DownloadAudit {
    private const val TAG = "DownloadAudit"
    private const val UNDO_KEY = "download_reorganize_undo"

    data class Download(
        val videoId: String,
        val meta: SavedSongMetadata,
        val uri: Uri,
        /** Below the download folder now, or just the file name for something filed elsewhere. */
        val current: String,
        /** Where the layout would put it. */
        val expected: String,
        val sizeBytes: Long,
    ) {
        val misplaced: Boolean get() = DownloadLayout.key(current) != DownloadLayout.key(expected)
    }

    data class Orphan(val uri: Uri, val name: String, val sizeBytes: Long)

    data class Report(
        val downloads: List<Download>,
        val orphans: List<Orphan>,
        /** Groups of two or more downloads that are the same recording. */
        val duplicates: List<List<Download>>,
    ) {
        val misplaced: List<Download> get() = downloads.filter { it.misplaced }
        val totalBytes: Long get() = downloads.sumOf { it.sizeBytes } + orphans.sumOf { it.sizeBytes }
    }

    suspend fun scan(context: Context): Report = withContext(Dispatchers.IO) {
        val saved = Downloads.saved.value
        val seen = HashSet<String>()
        val downloads = Downloads.savedMetadata.value.values
            .filter { seen.add(it.uri) }
            .mapNotNull { meta ->
                val uri = Uri.parse(meta.uri)
                if (!DownloadStore.exists(context, uri)) return@mapNotNull null
                val current = DownloadStore.relativeName(context, uri) ?: return@mapNotNull null
                val extension = DownloadLayout.fileNameOf(current).substringAfterLast('.', "m4a")
                Download(
                    videoId = meta.videoId,
                    meta = meta,
                    uri = uri,
                    current = current,
                    expected = Downloads.expectedName(meta, extension),
                    sizeBytes = DownloadStore.sizeOf(context, uri),
                )
            }
        val known = saved.values.toHashSet()
        val orphans = DownloadStore.listAudio(context)
            .filter { it.uri.toString() !in known }
            .map { Orphan(it.uri, it.name, it.sizeBytes) }
        Report(downloads, orphans, duplicatesOf(downloads))
    }

    private fun duplicatesOf(downloads: List<Download>): List<List<Download>> {
        val buckets = downloads.groupBy { PlaylistMatcher.normalize(it.meta.title) }.filterKeys { it.isNotEmpty() }
        return buckets.values.flatMap { bucket ->
            val songs = bucket.map { it to it.asSong() }
            val used = HashSet<Int>()
            songs.indices.mapNotNull { i ->
                if (i in used) return@mapNotNull null
                val group = songs.indices.filter { j -> j == i || (j !in used && SongIdentity.same(songs[i].second, songs[j].second)) }
                if (group.size < 2) return@mapNotNull null
                used += group
                group.map { songs[it].first }
            }
        }
    }

    private fun Download.asSong() = Song(
        videoId = videoId, title = meta.title, artist = meta.artist, thumbnailUrl = null,
        durationText = meta.durationText, albumName = meta.albumName,
    )

    // ---- Moving -------------------------------------------------------------

    @Serializable
    private data class Move(val oldUri: String, val newUri: String, val oldName: String, val newName: String, val oldLyrics: String? = null, val newLyrics: String? = null)

    data class MoveResult(val moved: Int, val failed: Int)

    /** Moves [downloads] to where the layout puts them, remembering how to undo it. */
    suspend fun reorganize(context: Context, downloads: List<Download>, onProgress: (Int, Int) -> Unit = { _, _ -> }): MoveResult =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<Move>()
            var failed = 0
            downloads.forEachIndexed { index, d ->
                val move = runCatching { moveOne(context, d, d.expected) }.getOrNull()
                if (move != null) log += move else failed++
                onProgress(index + 1, downloads.size)
            }
            if (log.isNotEmpty()) prefs(context).edit().putString(UNDO_KEY, Json.encodeToString(log)).apply()
            MoveResult(log.size, failed)
        }

    fun canUndo(context: Context): Boolean = prefs(context).getString(UNDO_KEY, null) != null

    suspend fun undo(context: Context): MoveResult = withContext(Dispatchers.IO) {
        val log = prefs(context).getString(UNDO_KEY, null)?.let { runCatching { Json.decodeFromString<List<Move>>(it) }.getOrNull() }
            ?: return@withContext MoveResult(0, 0)
        var moved = 0
        var failed = 0
        for (m in log) {
            val meta = Downloads.savedMetadata.value.values.firstOrNull { it.uri == m.newUri }
            val d = Download(
                videoId = meta?.videoId.orEmpty(), meta = meta ?: continue, uri = Uri.parse(m.newUri),
                current = m.newName, expected = m.oldName, sizeBytes = 0,
            )
            if (runCatching { moveOne(context, d, m.oldName) }.getOrNull() != null) moved++ else failed++
        }
        prefs(context).edit().remove(UNDO_KEY).apply()
        MoveResult(moved, failed)
    }

    private fun moveOne(context: Context, d: Download, target: String): Move? {
        val old = d.uri
        val newUri: Uri
        if (old.scheme == "file") {
            val source = File(old.path ?: return null)
            val root = File(context.filesDir, "downloads")
            var dest = File(root, target)
            if (dest.exists() && dest != source) dest = File(dest.parentFile, uniqueName(dest))
            dest.parentFile?.mkdirs()
            if (!source.renameTo(dest)) return null
            newUri = Uri.fromFile(dest)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, DownloadLayout.fileNameOf(target))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/${DownloadStore.FOLDER}/" + DownloadLayout.folderOf(target).let { if (it.isEmpty()) "" else "$it/" })
            }
            // A file somebody else put there is not this app's to rename; the store says so with an exception.
            if (context.contentResolver.update(old, values, null, null) < 1) return null
            newUri = old
        }
        val lyrics = moveLyrics(context, d.meta.lyricsUri, target)
        Downloads.relocate(old.toString(), newUri.toString(), lyrics?.second)
        return Move(old.toString(), newUri.toString(), d.current, target, d.meta.lyricsUri, lyrics?.second)
    }

    /** The lyrics file beside a track follows it: same path, `.lrc`/`.ttml` extension kept. */
    private fun moveLyrics(context: Context, lyricsUri: String?, audioTarget: String): Pair<String, String>? {
        val old = lyricsUri?.let(Uri::parse)?.takeIf { it.scheme == "file" } ?: return null
        val source = File(old.path ?: return null)
        if (!source.exists()) return null
        val dest = File(LyricsSidecarStore.storageDir(context), audioTarget.substringBeforeLast('.') + "." + source.extension)
        if (dest == source) return null
        dest.parentFile?.mkdirs()
        return if (source.renameTo(dest)) old.toString() to Uri.fromFile(dest).toString() else null
    }

    private fun uniqueName(file: File): String {
        var n = 2
        while (true) {
            val candidate = "${file.nameWithoutExtension} ($n).${file.extension}"
            if (!File(file.parentFile, candidate).exists()) return candidate
            n++
        }
    }

    // ---- Cleaning -----------------------------------------------------------

    suspend fun deleteOrphans(context: Context, orphans: List<Orphan>): Int = withContext(Dispatchers.IO) {
        orphans.count { DownloadStore.delete(context, it.uri) }
    }

    /** Deletes every download of [group] but [keep], files and records both. */
    suspend fun deleteExtras(context: Context, group: List<Download>, keep: Download): Int = withContext(Dispatchers.IO) {
        group.filter { it.uri != keep.uri }.count { Downloads.delete(context, it.videoId) }
    }

    /** The copy of a duplicate group worth keeping: the biggest file, which is the highest quality rendition. */
    fun bestOf(group: List<Download>): Download = group.maxBy { it.sizeBytes }

    private fun prefs(context: Context) = context.getSharedPreferences("auden_storage", Context.MODE_PRIVATE)

    init { Log.d(TAG, "ready") }
}
