package dev.auden.library.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.LocalPlaylists
import dev.auden.library.db.AudenDb
import dev.auden.library.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** A file format a playlist can be written as. */
enum class PlaylistFormat(val extension: String, val mime: String) {
    /** What other players read. */
    M3U8("m3u8", "audio/x-mpegurl"),
    XSPF("xspf", "application/xspf+xml"),
    /** Auden's own, which keeps streamed songs that a path cannot describe. */
    AUDEN("auden.json", "application/json"),
}

data class ImportSummary(
    val name: String,
    val total: Int,
    /** Found in the library on this device. */
    val local: Int,
    /** Found by searching YouTube Music. */
    val online: Int,
    val unresolved: List<PlaylistEntry>,
)

/** Writes playlists to, and reads them from, files the listener picks with the system file dialog. */
object PlaylistTransfer {
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Suggested file name for the system "save as" dialog. */
    fun fileNameFor(playlist: LocalPlaylist, format: PlaylistFormat) =
        Paths.safeFileName(playlist.title) + "." + format.extension

    suspend fun export(context: Context, playlist: LocalPlaylist, format: PlaylistFormat, target: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val entries = playlist.songs.map(::entryOf)
                // The destination folder is not knowable through the system dialog, so paths stay absolute.
                val text = when (format) {
                    PlaylistFormat.M3U8 -> M3u.write(playlist.title, entries, baseDir = null)
                    PlaylistFormat.XSPF -> Xspf.write(playlist.title, entries, baseDir = null)
                    PlaylistFormat.AUDEN -> AudenJson.write(playlist.title, entries)
                }
                context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }

    /**
     * Reads [source] into a new device playlist.
     *
     * Lines are matched against the library first ([PlaylistMatcher]); what is
     * left is searched on YouTube Music when [searchOnline] is set, which is
     * what makes a playlist from another player or machine usable here.
     */
    suspend fun import(
        context: Context,
        source: Uri,
        searchOnline: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "playlist"
            val bytes = context.contentResolver.openInputStream(source)!!.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES) { "playlist file is too large" }
            val parsed = PlaylistFormats.parse(displayName, bytes.toString(Charsets.UTF_8), baseDir = null)
                ?: error("unreadable playlist")
            require(parsed.entries.isNotEmpty()) { "playlist is empty" }

            val matcher = PlaylistMatcher(AudenDb.get(context).tracks().all())
            val slots = arrayOfNulls<Song>(parsed.entries.size)
            val missing = mutableListOf<Int>()
            var local = 0
            parsed.entries.forEachIndexed { i, entry ->
                val hit = matcher.match(entry)
                when {
                    hit != null -> { slots[i] = hit.toSong(); local++ }
                    // A file Auden wrote carries the id, so no search is needed to stream it.
                    !entry.videoId.isNullOrBlank() -> { slots[i] = songOf(entry); local++ }
                    else -> missing += i
                }
            }

            val online = java.util.concurrent.atomic.AtomicInteger()
            if (searchOnline && missing.isNotEmpty()) {
                val done = java.util.concurrent.atomic.AtomicInteger()
                val gate = Semaphore(4)
                coroutineScope {
                    missing.map { i ->
                        async {
                            gate.withPermit {
                                val e = parsed.entries[i]
                                val title = e.title ?: Paths.fileName(e.path.orEmpty()).substringBeforeLast('.')
                                val found = if (title.isBlank()) null else runCatching {
                                    SpotifyImporter.matchTrack(
                                        SpotifyTrack("", title, e.artist.orEmpty(), e.album, ((e.durationSec ?: 0.0) * 1000).toInt(), null),
                                    )
                                }.getOrNull()
                                if (found != null) { slots[i] = found; online.incrementAndGet() }
                                onProgress(done.incrementAndGet(), missing.size)
                            }
                        }
                    }.awaitAll()
                }
            }

            val name = parsed.name ?: displayName.substringBeforeLast('.').removeSuffix(".auden")
            val songs = slots.filterNotNull()
            require(songs.isNotEmpty()) { "no track could be matched" }
            LocalPlaylists.create(name, songs)
            ImportSummary(
                name = name,
                total = parsed.entries.size,
                local = local,
                online = online.get(),
                unresolved = missing.filter { slots[it] == null }.map { parsed.entries[it] },
            )
        }
    }

    private fun entryOf(song: Song) = PlaylistEntry(
        path = song.localPath,
        title = song.title,
        artist = song.artist,
        album = song.albumName,
        durationSec = song.durationText?.let(::secondsOf),
        videoId = song.videoId.takeIf { it.isNotBlank() && !it.startsWith("content:") && !it.startsWith("/") },
    )

    private fun songOf(entry: PlaylistEntry) = Song(
        videoId = entry.videoId.orEmpty(),
        title = entry.title.orEmpty(),
        artist = entry.artist.orEmpty(),
        thumbnailUrl = null,
        durationText = entry.durationSec?.toLong()?.let { "%d:%02d".format(it / 60, it % 60) },
        albumName = entry.album,
    )

    /** "3:45" or "1:02:03" in seconds. */
    private fun secondsOf(text: String): Double? =
        text.split(':').map { it.toIntOrNull() ?: return null }.fold(0) { acc, part -> acc * 60 + part }.toDouble().takeIf { it > 0 }
}
