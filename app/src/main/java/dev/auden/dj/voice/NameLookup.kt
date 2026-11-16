package dev.auden.dj.voice

import android.content.Context
import com.music.bitchord.data.Http
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * A readable form for an artist whose name is written in a script the DJ's voices cannot read (Hangul, kana, Cyrillic,
 * Arabic…), from MusicBrainz: the artist's primary English alias, or its sort name in Latin letters. Asked once per
 * name and remembered, including the "none" answer, so a session never asks twice and the network is only used the
 * first time an artist like that comes round. A name the lookup cannot answer in time is spoken as it is.
 */
class NameLookup(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** What is already known for [name]: the alias, an empty string for "looked, nothing found", null for never asked. */
    fun cached(name: String): String? = prefs.getString(DjPronunciation.fold(name).ifEmpty { name }, null)

    /** The readable form of [name], from memory or MusicBrainz; null when there is none (or it could not be reached). */
    suspend fun aliasOf(name: String): String? {
        val key = DjPronunciation.fold(name).ifEmpty { name }
        prefs.getString(key, null)?.let { return it.ifEmpty { null } }
        val found = withTimeoutOrNull(TIMEOUT_MS) { runCatching { fetch(name) }.getOrNull() }
        // Only a real answer is remembered: a timeout or a failure is asked again another time.
        if (found != null) prefs.edit().putString(key, found.orEmpty()).apply()
        return found?.ifEmpty { null }
    }

    /** Null when the answer could not be had; "" when MusicBrainz answered and had nothing readable. */
    private fun fetch(name: String): String? {
        val url = "https://musicbrainz.org/ws/2/artist".toHttpUrl().newBuilder()
            .addQueryParameter("query", "artist:\"$name\"")
            .addQueryParameter("fmt", "json")
            .addQueryParameter("limit", "1")
            .build()
        // MusicBrainz asks every client to say what it is: a generic name, nothing about the listener.
        val request = Request.Builder().url(url).header("User-Agent", "Auden/1.0 (pronunciation lookup)").build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string() ?: return null
        }
        val artist = (json.parseToJsonElement(body).jsonObject["artists"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return ""
        val aliases = (artist["aliases"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val primaryEnglish = aliases.firstOrNull { a ->
            a["primary"]?.jsonPrimitive?.booleanOrNull == true && a["locale"]?.jsonPrimitive?.contentOrNull?.startsWith("en") == true
        }?.get("name")?.jsonPrimitive?.contentOrNull
        val sort = artist["sort-name"]?.jsonPrimitive?.contentOrNull
        return listOfNotNull(primaryEnglish, sort).firstOrNull { !DjPronunciation.needsAlias(it) && it.isNotBlank() }.orEmpty()
    }

    private companion object {
        const val PREFS = "auden_dj_names"
        const val TIMEOUT_MS = 2_500L
    }
}
