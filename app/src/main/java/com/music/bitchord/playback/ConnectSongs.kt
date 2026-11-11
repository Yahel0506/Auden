package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The songs of a Connect session as this phone knows them, rebuilt from YouTube Music rather than taken from
 * the device that sent the queue.
 *
 * A queue travels as a window of `{videoId, title, artist, artworkUrl}`, and the artwork a sender can put there
 * is whatever it happens to have: nothing for a song it holds as a file, an address the phone cannot load, a
 * size nobody asked for. Showing that left a mirrored session with covers only for songs downloaded here. So
 * the phone keeps what arrives as a stopgap, asks for each song's own record (title, artist, album, cover,
 * artist and album pages) and shows that, remembering it for the next session. Playback state is still the
 * sender's; what is drawn for each song is the phone's own.
 */
internal object ConnectSongs {
    private const val TAG = "BitChord"
    private const val FILE = "connect_songs.json"
    private const val MAX_KEPT = 1500
    private const val RETRY_AFTER_MS = 60_000L

    private val songs = object : LinkedHashMap<String, Song>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) = size > MAX_KEPT
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(4)

    @Volatile private var file: File? = null

    /** Reads what earlier sessions left. Idempotent. */
    fun load(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE)
        file = target
        runCatching {
            if (!target.isFile) return
            val rows = JSONArray(target.readText())
            synchronized(songs) {
                for (i in 0 until rows.length()) songFrom(rows.getJSONObject(i))?.let { songs[it.videoId] = it }
            }
        }.onFailure { Log.w(TAG, "connect songs unreadable: ${it.message}") }
    }

    fun cached(videoId: String): Song? = synchronized(songs) { songs[videoId] }

    /**
     * Fetches the songs of [ids] that are not known yet, a few at a time, calling [onResolved] (on the main thread)
     * for each one that arrives. A song that failed is not asked again for a minute.
     */
    fun resolveMissing(scope: CoroutineScope, ids: List<String>, onResolved: (String) -> Unit) {
        val now = System.currentTimeMillis()
        for (id in ids.distinct()) {
            if (cached(id) != null) continue
            if ((failedAt[id] ?: 0L) + RETRY_AFTER_MS > now) continue
            if (!pending.add(id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val song = gate.withPermit { YtMusicRepository.trackLinks(id).getOrNull() }
                    if (song == null || song.thumbnailUrl.isNullOrBlank()) {
                        failedAt[id] = System.currentTimeMillis()
                        return@launch
                    }
                    val own = song.copy(
                        videoId = id,
                        setVideoId = null,
                        queueTier = QueueTier.CONTEXT,
                    )
                    synchronized(songs) { songs[id] = own }
                    persist()
                    withContext(Dispatchers.Main) { onResolved(id) }
                } finally {
                    pending.remove(id)
                }
            }
        }
    }

    private fun persist() {
        val target = file ?: return
        runCatching {
            val rows = JSONArray()
            synchronized(songs) { songs.values.forEach { rows.put(songTo(it)) } }
            target.writeText(rows.toString())
        }.onFailure { Log.w(TAG, "connect songs not saved: ${it.message}") }
    }

    private fun songTo(song: Song) = JSONObject()
        .put("id", song.videoId)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("thumb", song.thumbnailUrl)
        .put("duration", song.durationText)
        .put("artistId", song.artistId)
        .put("albumId", song.albumId)
        .put("album", song.albumName)
        .put("artists", JSONArray().also { a -> song.artists.forEach { a.put(JSONObject().put("n", it.name).put("b", it.browseId)) } })

    private fun songFrom(o: JSONObject): Song? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val thumb = o.optString("thumb").takeIf { it.startsWith("http") } ?: return null
        fun text(key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
        val artists = o.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { ArtistRef(it.optString("n"), it.optString("b").takeIf { b -> b.isNotBlank() && b != "null" }) }
            }
        }.orEmpty()
        return Song(
            videoId = id,
            title = o.optString("title"),
            artist = o.optString("artist"),
            thumbnailUrl = thumb,
            durationText = text("duration"),
            artistId = text("artistId"),
            artists = artists,
            albumId = text("albumId"),
            albumName = text("album"),
            queueTier = QueueTier.CONTEXT,
        )
    }
}
