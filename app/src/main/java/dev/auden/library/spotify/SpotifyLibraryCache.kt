package dev.auden.library.spotify

import android.content.Context
import com.music.bitchord.data.spotify.SpotifyAlbum
import com.music.bitchord.data.spotify.SpotifyArtist
import com.music.bitchord.data.spotify.SpotifyPlaylist
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The last playlists, albums and artists seen in the Spotify library, so the page opens at once and offline. */
object SpotifyLibraryCache {
    private const val PREFS = "auden_spotify_cache"
    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playlists(context: Context): List<SpotifyPlaylist> = read(context, "playlists", ListSerializer(SpotifyPlaylist.serializer()))
    fun albums(context: Context): List<SpotifyAlbum> = read(context, "albums", ListSerializer(SpotifyAlbum.serializer()))
    fun artists(context: Context): List<SpotifyArtist> = read(context, "artists", ListSerializer(SpotifyArtist.serializer()))

    fun savePlaylists(context: Context, value: List<SpotifyPlaylist>) = write(context, "playlists", ListSerializer(SpotifyPlaylist.serializer()), value)
    fun saveAlbums(context: Context, value: List<SpotifyAlbum>) = write(context, "albums", ListSerializer(SpotifyAlbum.serializer()), value)
    fun saveArtists(context: Context, value: List<SpotifyArtist>) = write(context, "artists", ListSerializer(SpotifyArtist.serializer()), value)

    /** Forgets everything, for when the account is disconnected. */
    fun clear(context: Context) = prefs(context).edit().clear().apply()

    private fun <T> read(context: Context, key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        runCatching { json.decodeFromString(serializer, prefs(context).getString(key, null) ?: "[]") }.getOrDefault(emptyList())

    private fun <T> write(context: Context, key: String, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>) {
        prefs(context).edit().putString(key, json.encodeToString(serializer, value)).apply()
    }
}
