package dev.auden.transfer.services

import android.util.Base64
import kotlinx.serialization.json.JsonObject

private const val API = "https://api.tidal.com/v1"
private const val SERVICE = "Tidal"

/** The web player's token, plus the user id and country every library call needs. */
object TidalConnector : AccountConnector {
    override suspend fun connect(credentials: CapturedCredentials): AccountLibrary? {
        for (token in credentials.untriedBearers("tidal.com")) {
            val auth = mapOf("Authorization" to "Bearer $token")
            val response = AccountHttp.get("$API/sessions", auth, SERVICE)
            when (response.status) {
                200 -> {
                    val session = AccountHttp.parse(response.body)
                    val userId = session?.idString("userId")
                    val country = session?.string("countryCode")
                    if (userId != null && country != null) return TidalLibrary(auth, userId, country)
                    credentials.markTried(token)
                }
                429 -> throw AccountRetryLater(response.retryAfterSeconds?.coerceIn(2, 30) ?: 5)
                else -> {
                    // Some tokens are refused by /sessions but carry the user in the token itself.
                    jwtUser(token)?.let { (userId, country) ->
                        val probe = AccountHttp.get("$API/users/$userId/playlists?limit=1&countryCode=$country", auth, SERVICE)
                        if (probe.status == 200) return TidalLibrary(auth, userId, country)
                    }
                    credentials.markTried(token)
                }
            }
        }
        return null
    }

    private fun jwtUser(token: String): Pair<String, String>? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val claims = AccountHttp.parse(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))) ?: return null
        val userId = claims.idString("uid") ?: return null
        val country = claims.string("cc") ?: return null
        userId to country
    }.getOrNull()
}

/** Tidal image ids are UUIDs whose dashes become path separators. */
private fun tidalImage(id: String?, size: String): String? = id?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/$size.jpg" }

internal fun tidalTrack(track: JsonObject): ServiceTrack? {
    val title = track.string("title") ?: return null
    val version = track.string("version")
    return ServiceTrack(
        id = track.idString("id") ?: title,
        title = if (version != null && !title.contains(version, ignoreCase = true)) "$title ($version)" else title,
        artist = track["artists"].array().mapNotNull { it.obj()?.string("name") }.joinToString(", ")
            .ifEmpty { track["artist"].obj()?.string("name").orEmpty() },
        album = track["album"].obj()?.string("title"),
        durationMs = track.long("duration")?.times(1000),
        imageUrl = tidalImage(track["album"].obj()?.string("cover"), "640x640"),
    )
}

private class TidalLibrary(
    private val headers: Map<String, String>,
    private val userId: String,
    private val country: String,
) : AccountLibrary {

    override suspend fun likedTracks(): List<ServiceTrack> {
        val out = mutableListOf<ServiceTrack>()
        forEachPage("$API/users/$userId/favorites/tracks?countryCode=$country&order=DATE&orderDirection=DESC", 100) { entry ->
            entry["item"].obj()?.let(::tidalTrack)?.let { out += it }
        }
        return out
    }

    override suspend fun playlists(): List<ServicePlaylist> {
        val result = mutableListOf<ServicePlaylist>()
        val seen = HashSet<String>()
        fun JsonObject.toPlaylist(subtitle: String): ServicePlaylist? {
            val id = string("uuid") ?: return null
            val cover = tidalImage(string("squareImage"), "640x640") ?: tidalImage(string("image"), "750x500")
            return ServicePlaylist(id, string("title") ?: "Untitled playlist", subtitle, cover)
        }
        forEachPage("$API/users/$userId/playlists?countryCode=$country", 50) { item ->
            item.toPlaylist("")?.takeIf { seen.add(it.id) }?.let { result += it }
        }
        forEachPage("$API/users/$userId/favorites/playlists?countryCode=$country", 50) { entry ->
            entry["item"].obj()?.toPlaylist("Saved playlist")?.takeIf { seen.add(it.id) }?.let { result += it }
        }
        return result
    }

    override suspend fun playlistTracks(id: String): List<ServiceTrack> = itemsAt("$API/playlists/$id/items?countryCode=$country")

    override suspend fun albums(): List<ServiceAlbum> {
        val out = mutableListOf<ServiceAlbum>()
        forEachPage("$API/users/$userId/favorites/albums?countryCode=$country", 50) { entry ->
            val a = entry["item"].obj() ?: return@forEachPage
            val id = a.idString("id") ?: return@forEachPage
            out += ServiceAlbum(
                id, a.string("title") ?: return@forEachPage,
                a["artists"].array().mapNotNull { it.obj()?.string("name") }.joinToString(", ").ifEmpty { a["artist"].obj()?.string("name").orEmpty() },
                tidalImage(a.string("cover"), "640x640"),
            )
        }
        return out
    }

    override suspend fun albumTracks(id: String): List<ServiceTrack> = itemsAt("$API/albums/$id/items?countryCode=$country")

    override suspend fun artists(): List<ServiceArtist> {
        val out = mutableListOf<ServiceArtist>()
        forEachPage("$API/users/$userId/favorites/artists?countryCode=$country", 50) { entry ->
            val a = entry["item"].obj() ?: return@forEachPage
            val id = a.idString("id") ?: return@forEachPage
            out += ServiceArtist(id, a.string("name") ?: return@forEachPage, tidalImage(a.string("picture"), "480x480"))
        }
        return out
    }

    private suspend fun itemsAt(url: String): List<ServiceTrack> {
        val out = mutableListOf<ServiceTrack>()
        forEachPage(url, 100) { entry ->
            // Playlists can hold music videos too; only tracks have an album.
            val type = entry.string("type")
            if (type != null && type != "track") return@forEachPage
            entry["item"].obj()?.let(::tidalTrack)?.let { out += it }
        }
        return out
    }

    /** Offset paging until the reported total, or an empty page. */
    private suspend fun forEachPage(baseUrl: String, pageSize: Int, onItem: (JsonObject) -> Unit) {
        var offset = 0
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val sep = if ('?' in baseUrl) "&" else "?"
            val page = AccountHttp.getJson("$baseUrl${sep}limit=$pageSize&offset=$offset", headers, SERVICE, notFoundAsNull = true) ?: return
            val items = page["items"].array()
            items.forEach { it.obj()?.let(onItem) }
            offset += items.size
            val total = page.long("totalNumberOfItems")
            if (items.isEmpty() || (total != null && offset >= total)) return
        }
    }

    private companion object { const val MAX_PAGES = 400 }
}
