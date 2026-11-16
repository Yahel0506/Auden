package com.music.bitchord.data.canvas

import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Who the signed-in Spotify account is: its stable username, as Spotify's `/v1/me` reports it, for Auden Connect to
 * find a room by when there is no Google account. Asked for once per session cookie and kept (see
 * [AppSettings.spotifyAccountId]). Null when there is no Spotify session or the answer did not come.
 */
internal object SpotifyAccountId {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun read(): String? {
        AppSettings.spotifyAccountId.value.takeIf { it.isNotBlank() }?.let { return it }
        val token = SpotifyToken.accessToken() ?: return null
        val id = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me")
                    .header("Authorization", "Bearer $token")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    json.parseToJsonElement(response.body?.string().orEmpty())
                        .jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        AppSettings.setSpotifyAccountId(id)
        return id
    }
}
