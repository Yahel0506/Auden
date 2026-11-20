package com.music.bitchord.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An artist's main genre as Apple's catalogue files them — "Pop", "Latin", "Hip-Hop/Rap", in the store's own language —
 * off the same public artist search the artist photos use ([AppleArtistPhotos]). No key and no account: a name goes
 * out, a genre comes back. What the genre mixes are grouped by when nothing better (Last.fm) is to hand.
 */
object AppleArtistGenres {
    private val known = ConcurrentHashMap<String, String>()

    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Answers from an earlier process, misses (empty) included. */
    fun seed(genres: Map<String, String>) = genres.forEach { (k, v) -> known.putIfAbsent(k, v) }

    /** Everything known now, for [seed] next time. */
    fun snapshot(): Map<String, String> = HashMap(known)

    /** [artistName]'s genre, or null when Apple has no artist by that name. A network miss is not remembered. */
    suspend fun genreOf(artistName: String): String? {
        val key = AppleCatalogue.matchKey(artistName)
        if (key.isEmpty()) return null
        known[key]?.let { return it.ifEmpty { null } }
        val found = withContext(Dispatchers.IO) {
            runCatching { lookup(artistName, key) }.getOrElse { return@withContext null } ?: ""
        } ?: return null
        known[key] = found
        return found.ifEmpty { null }
    }

    private fun lookup(artistName: String, key: String): String? {
        val locale = Locale.getDefault()
        val country = locale.country.ifBlank { "US" }.lowercase(Locale.ROOT)
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}" +
            "&entity=musicArtist&limit=5&country=$country&lang=${locale.language}_${country}"
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", "Auden/2 (Android)").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        return results.asSequence()
            .map { it.jsonObject }
            .firstOrNull { AppleCatalogue.matchKey(it["artistName"]?.jsonPrimitive?.content.orEmpty()) == key }
            ?.get("primaryGenreName")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }
}
