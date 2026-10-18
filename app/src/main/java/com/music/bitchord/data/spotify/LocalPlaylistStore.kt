package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.data.model.Song
import org.json.JSONArray

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /** The cloud page this copy was saved from, if it was. */
    val sourceId: String? = null,
) {
    val browseId: String get() = "local:playlist:$id"
}

/**
 * Playlists kept on this device. Backed by Auden's database (see
 * [dev.auden.library.LocalPlaylists]); this object keeps the old API so the
 * screens that already use it did not have to change.
 */
object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    val playlists get() = dev.auden.library.LocalPlaylists.playlists

    fun init(context: Context) = dev.auden.library.LocalPlaylists.init(context)

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist =
        dev.auden.library.LocalPlaylists.create(title, songs)

    fun deletePlaylist(id: String) = dev.auden.library.LocalPlaylists.delete(id.removePrefix("local:playlist:"))

    fun renamePlaylist(id: String, newTitle: String) =
        dev.auden.library.LocalPlaylists.rename(id.removePrefix("local:playlist:").removePrefix("VL"), newTitle)

    fun getPlaylist(id: String): LocalPlaylist? = dev.auden.library.LocalPlaylists.get(id)

    /** What the pre-database store left in preferences; read once by the migration. */
    internal fun readLegacy(context: Context): List<LocalPlaylist> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val songsArray = obj.getJSONArray("songs")
                val songs = (0 until songsArray.length()).map { j ->
                    val sObj = songsArray.getJSONObject(j)
                    fun str(key: String) = sObj.optString(key, null).takeIf { !it.isNullOrBlank() && it != "null" }
                    Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = str("thumbnailUrl"),
                        durationText = str("durationText"),
                        artistId = str("artistId"),
                        albumId = str("albumId"),
                        albumName = str("albumName"),
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                }
                list.add(LocalPlaylist(obj.getString("id"), obj.getString("title"), songs))
            }
            list
        }.getOrDefault(emptyList())
    }
}
