package dev.auden.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import dev.auden.library.db.AudenDb
import dev.auden.library.db.TrackEntity
import dev.auden.library.db.TrackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Keeps [AudenDb] in step with the music on the device.
 *
 * Works the way a file-manager style indexer does: list what is on disk, compare
 * it with what is already stored by path, and only touch the difference. A file
 * is new when its uri is unknown, changed when its modified time or size moved,
 * and gone when it is stored but no longer listed. What counts as music at all
 * (minimum length, ringtones, the chosen folder) is decided by
 * [LocalMediaRepository.getLocalMusic], so Auden and the existing local-music
 * pages never disagree about what is in the library.
 */
object LibraryIndexer {
    private const val TAG = "LibraryIndexer"

    sealed interface State {
        data object Idle : State
        data object Scanning : State
        data class Done(val added: Int, val changed: Int, val removed: Int) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()

    /** Reads the device once and reconciles the database. Safe to call often; concurrent calls queue. */
    suspend fun sync(context: Context): State = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext _state.value
        try {
            _state.value = State.Scanning
            if (!LocalMediaRepository.hasStoragePermission(context)) {
                // Without access the list is empty, which must not read as "everything was deleted".
                return@withContext State.Failed("no storage permission").also { _state.value = it }
            }
            val db = AudenDb.get(context)
            val songs = LocalMediaRepository.getLocalMusic(context).filter { it.localUri?.startsWith("content:") == true }
            val extras = readExtras(context)
            val known = db.tracks().localRefs().associateBy { it.localUri }
            val listed = songs.mapTo(HashSet()) { it.localUri!! }

            var added = 0
            var changed = 0
            val gone = known.values.filter { it.localUri !in listed }
            // A scan that finds nothing while the library holds files is a failed scan, not a purge.
            if (songs.isEmpty() && known.isNotEmpty()) {
                return@withContext State.Failed("scan returned no files").also { _state.value = it }
            }

            db.withTransaction {
                for (song in songs) {
                    val uri = song.localUri!!
                    val entity = song.toIndexedTrack(extras[uri])
                    val existing = known[uri]
                    when {
                        existing == null -> {
                            // Same song already known from streaming: link the file to that row.
                            val linked = song.videoId.takeIf { !it.startsWith("content:") }?.let { db.tracks().byVideoId(it) }
                            if (linked != null && linked.localUri == null) {
                                db.tracks().update(entity.copy(id = linked.id, addedAt = linked.addedAt, videoId = linked.videoId, origin = linked.origin))
                            } else {
                                db.tracks().insert(entity)
                            }
                            added++
                        }
                        existing.modifiedAt != entity.modifiedAt || existing.sizeBytes != entity.sizeBytes || existing.path == null -> {
                            db.tracks().byId(existing.id)?.let { old ->
                                db.tracks().update(
                                    entity.copy(
                                        id = old.id, addedAt = old.addedAt, videoId = old.videoId ?: entity.videoId,
                                        spotifyId = old.spotifyId, isrc = old.isrc,
                                        identStatus = old.identStatus, identSource = old.identSource,
                                        suggestedVideoId = old.suggestedVideoId, suggestedTitle = old.suggestedTitle,
                                        suggestedArtist = old.suggestedArtist, identifiedAt = old.identifiedAt,
                                    ),
                                )
                            }
                            changed++
                        }
                    }
                }
                val streamable = gone.filter { it.videoId != null && !it.videoId.startsWith("content:") }.map { it.id }
                val vanished = gone.map { it.id } - streamable.toSet()
                streamable.chunked(500).forEach { db.tracks().detachFiles(it) }
                vanished.chunked(500).forEach { db.tracks().delete(it) }
            }
            dev.auden.library.links.LocalLinks.rebuild(context)
            State.Done(added, changed, gone.size).also {
                _state.value = it
                Log.i(TAG, "indexed: +$added ~$changed -${gone.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "index failed", e)
            State.Failed(e.message ?: "unknown").also { _state.value = it }
        } finally {
            lock.unlock()
        }
    }

    private class Extra(val sizeBytes: Long?, val year: Int?, val genre: String?, val albumArtist: String?, val disc: Int?)

    /** The columns [LocalMediaRepository.getLocalMusic] does not surface, keyed by content uri. */
    private fun readExtras(context: Context): Map<String, Extra> {
        val sdk30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.YEAR)
            if (sdk30) {
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
                add(MediaStore.Audio.Media.DISC_NUMBER)
            }
        }.toTypedArray()
        val out = HashMap<String, Extra>()
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val year = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val genre = if (sdk30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                val albumArtist = if (sdk30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                val disc = if (sdk30) c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else -1
                fun str(i: Int) = if (i >= 0) c.getString(i)?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" } else null
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)).toString()
                    out[uri] = Extra(
                        sizeBytes = c.getLong(size).takeIf { it > 0 },
                        year = c.getInt(year).takeIf { it > 0 },
                        genre = str(genre),
                        albumArtist = str(albumArtist),
                        disc = if (disc >= 0) c.getString(disc)?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 } else null,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "extra columns unavailable", it) }
        return out
    }

    private fun Song.toIndexedTrack(extra: Extra?) = TrackEntity(
        videoId = videoId.takeIf { !it.startsWith("content:") },
        localUri = localUri,
        path = localPath,
        title = title,
        artist = artist,
        album = albumName,
        albumArtist = extra?.albumArtist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        trackNumber = trackNumber,
        discNumber = extra?.disc,
        genre = extra?.genre,
        year = extra?.year,
        folder = localPath?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() },
        sizeBytes = extra?.sizeBytes,
        modifiedAt = localDateModifiedSeconds,
        origin = if (videoId.startsWith("content:")) TrackOrigin.LOCAL else TrackOrigin.DOWNLOAD,
    )
}
