package dev.auden.transfer.services

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val HOST = "https://amp-api.music.apple.com"
private const val SERVICE = "Apple Music"

/** Names iOS gives the auto playlist of loved songs; those are the likes. */
private val FAVORITES_NAMES = setOf("favorite songs", "favourite songs")

/**
 * Needs two tokens from the web player: the developer token (sent as the bearer) and the music-user token that only
 * exists once the listener signs in.
 */
object AppleMusicConnector : AccountConnector {
    override suspend fun connect(credentials: CapturedCredentials): AccountLibrary? {
        val userToken = credentials.appleUserToken ?: return null
        val developerTokens = credentials.untriedBearers("apple.com").ifEmpty { listOfNotNull(credentials.newestBearer("apple.com")) }
        for (developerToken in developerTokens) {
            val pairKey = "$developerToken|$userToken"
            if (credentials.isTried(pairKey)) continue
            val headers = appleHeaders(developerToken, userToken)
            val response = AccountHttp.get("$HOST/v1/me/storefront", headers, SERVICE)
            when (response.status) {
                200 -> return AppleMusicLibrary(headers)
                429 -> throw AccountRetryLater(response.retryAfterSeconds?.coerceIn(2, 30) ?: 5)
                else -> credentials.markTried(pairKey)
            }
        }
        return null
    }

    internal fun appleHeaders(developerToken: String, userToken: String) = mapOf(
        "Authorization" to "Bearer $developerToken",
        "Media-User-Token" to userToken,
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
        "Accept" to "application/json",
    )
}

/** Apple artwork URLs are templates: {w}x{h}, and sometimes {c} (crop) and {f} (format). */
internal fun appleArtwork(template: String): String =
    template.replace("{w}", "600").replace("{h}", "600").replace("{c}", "bb").replace("{f}", "jpg")

internal fun appleTrack(item: JsonObject): ServiceTrack? {
    val attributes = item["attributes"].obj() ?: return null
    val title = attributes.string("name") ?: return null
    return ServiceTrack(
        id = item.string("id") ?: title,
        title = title,
        artist = attributes.string("artistName").orEmpty(),
        album = attributes.string("albumName"),
        durationMs = attributes.long("durationInMillis"),
        imageUrl = attributes["artwork"].obj()?.string("url")?.let(::appleArtwork),
    )
}

private class AppleMusicLibrary(private val headers: Map<String, String>) : AccountLibrary {
    private var favoritesId: String? = null
    private var playlistsRead = false

    override suspend fun playlists(): List<ServicePlaylist> {
        val result = mutableListOf<ServicePlaylist>()
        forEachPage("$HOST/v1/me/library/playlists?limit=100") { item ->
            val id = item.string("id") ?: return@forEachPage
            val attributes = item["attributes"].obj()
            val name = attributes?.string("name") ?: "Untitled playlist"
            if (name.lowercase() in FAVORITES_NAMES) { favoritesId = id; return@forEachPage }
            result += ServicePlaylist(id, name, coverUrl = attributes?.get("artwork").obj()?.string("url")?.let(::appleArtwork))
        }
        playlistsRead = true
        return result
    }

    override suspend fun likedTracks(): List<ServiceTrack> {
        if (!playlistsRead) playlists()
        // The loved songs when the account has them; otherwise the songs saved to the library, the nearest thing it has.
        val url = favoritesId?.let { "$HOST/v1/me/library/playlists/$it/tracks?limit=100" } ?: "$HOST/v1/me/library/songs?limit=100"
        return tracksAt(url)
    }

    override suspend fun playlistTracks(id: String): List<ServiceTrack> = tracksAt("$HOST/v1/me/library/playlists/$id/tracks?limit=100")

    override suspend fun albums(): List<ServiceAlbum> {
        val out = mutableListOf<ServiceAlbum>()
        forEachPage("$HOST/v1/me/library/albums?limit=100") { item ->
            val id = item.string("id") ?: return@forEachPage
            val a = item["attributes"].obj() ?: return@forEachPage
            out += ServiceAlbum(id, a.string("name") ?: return@forEachPage, a.string("artistName").orEmpty(), a["artwork"].obj()?.string("url")?.let(::appleArtwork))
        }
        return out
    }

    override suspend fun albumTracks(id: String): List<ServiceTrack> = tracksAt("$HOST/v1/me/library/albums/$id/tracks?limit=100")

    override suspend fun artists(): List<ServiceArtist> {
        val out = mutableListOf<ServiceArtist>()
        forEachPage("$HOST/v1/me/library/artists?limit=100") { item ->
            val id = item.string("id") ?: return@forEachPage
            val name = item["attributes"].obj()?.string("name") ?: return@forEachPage
            out += ServiceArtist(id, name)
        }
        return out
    }

    private suspend fun tracksAt(url: String): List<ServiceTrack> {
        val out = mutableListOf<ServiceTrack>()
        forEachPage(url) { item -> appleTrack(item)?.let { out += it } }
        return out
    }

    /** Apple pages with a relative "next"; an empty list answers 404 instead of an empty page. */
    private suspend fun forEachPage(firstUrl: String, onItem: (JsonObject) -> Unit) {
        var next: String? = firstUrl
        var pages = 0
        while (next != null && pages++ < MAX_PAGES) {
            val page = AccountHttp.getJson(next, headers, SERVICE, notFoundAsNull = true) ?: return
            page["data"].array().forEach { it.obj()?.let(onItem) }
            next = (page["next"] as? JsonPrimitive)?.content
                ?.takeIf { it.startsWith("/v1/") }
                ?.let { path -> HOST + path + if ("limit=" in path) "" else (if ('?' in path) "&" else "?") + "limit=100" }
        }
    }

    private companion object { const val MAX_PAGES = 400 }
}
