package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A download that has been asked for and not finished, with what is needed to ask for it again. */
@Serializable
internal data class QueuedJob(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val isVideo: Boolean = false,
    val isExplicit: Boolean? = null,
    val from: String? = null,
    /** How many times it has already been tried and failed for a reason worth trying again. */
    val attempt: Int = 0,
) {
    fun toSong() = Song(
        videoId = videoId, title = title, artist = artist, thumbnailUrl = thumbnailUrl,
        durationText = durationText, albumName = albumName, artistId = artistId, albumId = albumId,
        isVideo = isVideo, isExplicit = isExplicit,
    )

    companion object {
        fun of(song: Song, from: String?, attempt: Int) = QueuedJob(
            videoId = song.videoId, title = song.title, artist = song.artist, thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText, albumName = song.albumName, artistId = song.artistId,
            albumId = song.albumId, isVideo = song.isVideo, isExplicit = song.isExplicit, from = from, attempt = attempt,
        )
    }
}

/**
 * The download queue, kept on disk so that a process death — the system
 * reclaiming memory, an update, a crash — does not lose what was waiting.
 *
 * Writes are coalesced: a 300-track album is 300 `put`s in a loop, and writing
 * the whole list after each would be quadratic. One write follows the burst.
 */
internal object QueueStore {
    private const val KEY = "download_queue_v1"
    private const val WRITE_DELAY_MS = 400L

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = LinkedHashMap<String, QueuedJob>()
    private var prefs: SharedPreferences? = null
    private var writing = false

    fun init(context: Context) {
        val p = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        prefs = p
        val stored = runCatching { json.decodeFromString<List<QueuedJob>>(p.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())
        synchronized(jobs) {
            jobs.clear()
            stored.forEach { jobs[it.videoId] = it }
        }
    }

    fun all(): List<QueuedJob> = synchronized(jobs) { jobs.values.toList() }

    fun put(song: Song, from: String?, attempt: Int = 0) {
        synchronized(jobs) { jobs[song.videoId] = QueuedJob.of(song, from, attempt) }
        scheduleWrite()
    }

    fun remove(videoId: String) {
        val removed = synchronized(jobs) { jobs.remove(videoId) != null }
        if (removed) scheduleWrite()
    }

    private fun scheduleWrite() {
        synchronized(jobs) {
            if (writing) return
            writing = true
        }
        scope.launch {
            delay(WRITE_DELAY_MS)
            val snapshot = synchronized(jobs) {
                writing = false
                jobs.values.toList()
            }
            prefs?.edit()?.putString(KEY, json.encodeToString(snapshot))?.apply()
        }
    }
}
