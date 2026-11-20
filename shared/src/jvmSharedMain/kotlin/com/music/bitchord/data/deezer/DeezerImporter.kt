package com.music.bitchord.data.deezer

import com.music.bitchord.data.Http
import com.music.bitchord.data.spotify.SpotifyImportTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/**
 * A public Deezer playlist or album, read from a pasted link through Deezer's open API (no key, no sign-in), as the
 * (title, artist) pairs the link import matches on YouTube Music. Ported from Mochy Music's link importer.
 */
object DeezerImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** deezer.com/<lang>/playlist/<id> and album/<id>, and the deezer.page.link short links once opened. */
    private val LINK = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(playlist|album)/(\d+)""", RegexOption.IGNORE_CASE)

    /** ("playlist" or "album", the id) a pasted link points at, or null for anything that is not a Deezer link. */
    fun parse(input: String): Pair<String, String>? =
        LINK.find(input.trim())?.destructured?.let { (type, id) -> type.lowercase() to id }

    suspend fun fetch(type: String, id: String): Pair<String, List<SpotifyImportTrack>> = withContext(Dispatchers.IO) {
        val info = get("https://api.deezer.com/$type/$id")
        val name = info.str("title") ?: "Deezer $type"
        val tracks = mutableListOf<SpotifyImportTrack>()
        var next: String? = "https://api.deezer.com/$type/$id/tracks?limit=100"
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val page = get(next)
            (page["data"] as? JsonArray)?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val title = obj.str("title") ?: return@forEach
                val artist = ((obj["artist"] as? JsonObject)?.str("name")).orEmpty()
                tracks += SpotifyImportTrack(title, artist)
            }
            next = page.str("next")?.takeIf { it.startsWith("https://api.deezer.com/") }
        }
        if (tracks.isEmpty()) error("That Deezer $type has no songs we can read.")
        name to tracks
    }

    private fun get(url: String): JsonObject {
        val request = Request.Builder().url(url).build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Deezer answered with an error (${response.code}).")
            response.body?.string().orEmpty()
        }
        val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Deezer sent something we couldn't read.")
        (obj["error"] as? JsonObject)?.let { e ->
            val message = e.str("message")
            error(if (message?.contains("no data", true) == true) "That Deezer playlist is private or doesn't exist." else "Deezer: ${message ?: "unknown error"}")
        }
        return obj
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private const val MAX_PAGES = 100
}
