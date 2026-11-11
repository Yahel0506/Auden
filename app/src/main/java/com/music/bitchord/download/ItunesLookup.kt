package com.music.bitchord.download

import android.util.Log
import com.music.bitchord.data.Http
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * A second place to ask for a song's album and cover when YouTube Music has no
 * certain match for a file: Apple's public catalogue search, which needs no key.
 * A hit is taken only when the title is the same and the artists overlap.
 */
internal object ItunesLookup {
    private const val TAG = "BitChord"

    class Hit(val album: String?, val artworkUrl: String?)

    fun find(title: String, artist: String): Hit? = runCatching {
        if (title.isBlank()) return null
        val term = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val request = okhttp3.Request.Builder()
            .url("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=10")
            .build()
        val body = Http.client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wantTitle = normalise(title)
        val wantArtist = normalise(artist)
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            if (normalise(row.optString("trackName")) != wantTitle) continue
            val got = normalise(row.optString("artistName"))
            if (wantArtist.isNotEmpty() && !(got.contains(wantArtist) || wantArtist.contains(got) ||
                    wantArtist.split(' ').firstOrNull()?.let { it.length > 2 && got.contains(it) } == true)
            ) continue
            val art = row.optString("artworkUrl100").takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "1200x1200bb")
            return Hit(album = row.optString("collectionName").takeIf { it.isNotBlank() }, artworkUrl = art)
        }
        null
    }.onFailure { Log.d(TAG, "itunes lookup failed: ${it.message}") }.getOrNull()

    internal fun normalise(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[\\(\\[].*?[\\)\\]]"), " ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
