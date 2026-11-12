package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files from this device the listener added to a playlist that lives somewhere else: a YouTube Music
 * or Spotify playlist, which this app only reads.
 *
 * Kept beside the playlist, never in it. Nothing here is sent to the account, and a sync of the
 * original cannot touch it, so the files stay put however the playlist changes upstream. Keyed by the
 * page's browse id and shown after the playlist's own tracks.
 */
object PlaylistLocalFiles {
    private const val PREF_NAME = "bitchord_playlist_local_files"
    private const val KEY = "files_json"

    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    private val _files = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val files: StateFlow<Map<String, List<Song>>> = _files.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _files.value = load()
    }

    /** Adds [songs] to [browseId]'s page, once each; returns how many were new. */
    fun add(browseId: String, songs: List<Song>): Int = synchronized(lock) {
        val have = _files.value[browseId].orEmpty()
        val known = have.mapTo(HashSet()) { it.localUri }
        val fresh = songs.filter { it.localUri != null && known.add(it.localUri) }
        if (fresh.isNotEmpty()) publish(_files.value + (browseId to have + fresh))
        fresh.size
    }

    fun remove(browseId: String, song: Song) = synchronized(lock) {
        val have = _files.value[browseId] ?: return@synchronized
        val kept = have.filterNot { it.localUri == song.localUri }
        publish(if (kept.isEmpty()) _files.value - browseId else _files.value + (browseId to kept))
    }

    fun has(browseId: String, song: Song): Boolean =
        song.localUri != null && _files.value[browseId].orEmpty().any { it.localUri == song.localUri }

    private fun publish(map: Map<String, List<Song>>) {
        _files.value = map
        runCatching {
            val root = JSONObject()
            map.forEach { (id, songs) ->
                val array = JSONArray()
                songs.forEach { s ->
                    array.put(
                        JSONObject()
                            .put("videoId", s.videoId)
                            .put("title", s.title)
                            .put("artist", s.artist)
                            .putOpt("thumbnailUrl", s.thumbnailUrl)
                            .putOpt("durationText", s.durationText)
                            .putOpt("albumName", s.albumName)
                            .putOpt("localUri", s.localUri)
                            .putOpt("localPath", s.localPath),
                    )
                }
                root.put(id, array)
            }
            prefs.edit().putString(KEY, root.toString()).apply()
        }
    }

    private fun load(): Map<String, List<Song>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { id ->
                val array = root.getJSONArray(id)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.getJSONObject(i)
                    fun text(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
                    val uri = text("localUri") ?: return@mapNotNull null
                    Song(
                        videoId = text("videoId") ?: uri,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        thumbnailUrl = text("thumbnailUrl"),
                        durationText = text("durationText"),
                        albumName = text("albumName"),
                        localUri = uri,
                        localPath = text("localPath"),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }
}
