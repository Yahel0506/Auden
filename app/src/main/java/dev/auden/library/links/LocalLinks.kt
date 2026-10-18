package dev.auden.library.links

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** One file on the device that is a given song. */
data class LocalCopy(
    val uri: String,
    /** Absolute path, where the platform gave one. */
    val path: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationText: String?,
    val sizeBytes: Long?,
    /** Kept in the app's own storage, which no other app can see. */
    val inAppStorage: Boolean,
    /** Downloaded by Auden itself, as opposed to a file the listener already had. */
    val downloadedByApp: Boolean,
) {
    val format: String? get() = (path ?: uri).substringAfterLast('.', "").takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }?.uppercase()
}

/**
 * Ties streamed songs to the files that already are them.
 *
 * A song in a playlist, a search or a feed carries a YouTube id and nothing
 * about the device. This finds, by metadata ([LocalMatchIndex]), any file in
 * the local library that is the same recording, so that song can be marked as
 * downloaded and played from the file. Nothing is written to disk: the links
 * are derived from the library index and recomputed when it changes, so moving
 * or deleting a file simply unlinks it.
 *
 * When more than one file matches — or a file and the app's own download —
 * every play picks one of them at random, and [copiesOf] lists them all.
 */
object LocalLinks {
    @Volatile private var index: LocalMatchIndex? = null
    private val cache = ConcurrentHashMap<String, List<LocalCopy>>()
    private val _revision = MutableStateFlow(0)

    /** Bumps whenever the library index is rebuilt, so lists can re-check their rows. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    suspend fun rebuild(context: Context) = withContext(Dispatchers.IO) {
        val tracks = AudenDb.get(context).tracks().all()
        index = LocalMatchIndex(tracks)
        android.util.Log.i("LocalLinks", "indexed ${index?.size} local tracks for matching")
        cache.clear()
        _revision.value += 1
    }

    /** Every copy on the device of [song], the app's own download included. */
    fun copiesOf(song: Song): List<LocalCopy> {
        if (song.videoId.isBlank() && song.localUri == null) return emptyList()
        val own = ownDownload(song)
        val found = cache.getOrPut(song.videoId) {
            index?.matches(song).orEmpty().map { track ->
                LocalCopy(
                    uri = track.localUri!!,
                    path = track.path,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationText = track.durationText,
                    sizeBytes = track.sizeBytes,
                    inAppStorage = false,
                    downloadedByApp = false,
                )
            }
        }
        // The indexer also sees files the app downloaded into shared storage; those are `own`, not a second copy.
        val ownUri = own?.uri
        val others = found.filter { it.uri != ownUri && !(own != null && it.path != null && it.path == own.path) }
        val all = listOfNotNull(own) + others
        // A song that is itself a file on the device is one of its own copies, even
        // if the index has not met it yet — this is what is playing, or listed, or held.
        val self = song.localUri?.takeIf { (it.startsWith("content:") || it.startsWith("file:")) && all.none { c -> c.uri == it } }
            ?.let { uri ->
                LocalCopy(
                    uri = uri,
                    path = song.localPath,
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    durationText = song.durationText,
                    sizeBytes = null,
                    inAppStorage = uri.startsWith("file:") && listOf("/Android/data/", "/data/user/", "/data/data/").any(uri::contains),
                    downloadedByApp = false,
                )
            }
        return all + listOfNotNull(self)
    }

    fun has(song: Song): Boolean = copiesOf(song).isNotEmpty()

    /** The file to play for [song]: one of its copies, chosen at random each time when there are several. */
    fun pickUri(song: Song): String? =
        copiesOf(song).filterNot { Downloads.isMissingLocalFile(it.uri) }.randomOrNull(Random.Default)?.uri

    private fun ownDownload(song: Song): LocalCopy? {
        val uri = Downloads.saved.value[song.videoId] ?: return null
        val meta = Downloads.savedMetadata.value[song.videoId]
        val inApp = uri.startsWith("file:") &&
            (uri.contains("/Android/data/") || uri.contains("/data/user/") || uri.contains("/data/data/"))
        return LocalCopy(
            uri = uri,
            path = if (uri.startsWith("file:")) uri.removePrefix("file://") else null,
            title = meta?.title ?: song.title,
            artist = meta?.artist ?: song.artist,
            album = meta?.albumName ?: song.albumName,
            durationText = meta?.durationText ?: song.durationText,
            sizeBytes = null,
            inAppStorage = inApp,
            downloadedByApp = true,
        )
    }
}
