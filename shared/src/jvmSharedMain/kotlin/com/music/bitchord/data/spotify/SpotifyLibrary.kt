package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

/**
 * Why a Spotify request did not work, in terms a screen can act on: sign in again, wait, check the connection.
 * An [IllegalStateException] so code that already catches that keeps working.
 */
class SpotifyException(
    val kind: Kind,
    message: String,
    /** How long Spotify asked to wait, for [Kind.RATE_LIMITED]. */
    val retryAfterSec: Int? = null,
) : IllegalStateException(message) {
    enum class Kind {
        /** No account is connected. */
        SIGNED_OUT,
        /** An account is connected but Spotify no longer accepts its session. */
        EXPIRED,
        RATE_LIMITED,
        NETWORK,
        UNKNOWN,
    }
}

@kotlinx.serialization.Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

@kotlinx.serialization.Serializable
data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val imageUrl: String?,
)

@kotlinx.serialization.Serializable
data class SpotifyArtist(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    /** Whether an account is connected, so a failed token can be told apart from there being none. */
    @Volatile
    var isSignedIn: () -> Boolean = { true }

    /** Called when Spotify refuses the token in use, so the next request mints a fresh one. */
    @Volatile
    var onTokenRejected: () -> Unit = {}

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) { withRetry { headers ->
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val (items, total, raw) = playlistPage(headers, offset)
            collected += items
            offset += raw
            if (raw == 0 || offset >= total) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
    } }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) { withRetry { headers ->
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = if (playlistId == LIKED_ID) {
                likedPage(headers, offset)
            } else {
                trackPage(headers, playlistId, offset)
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    } }

    /** Every album saved to the library. */
    suspend fun albums(): List<SpotifyAlbum> = withContext(Dispatchers.IO) { withRetry { headers ->
        collectLibrary(headers, "Albums", ::parseAlbumPage)
    } }

    /** Every artist followed. */
    suspend fun artists(): List<SpotifyArtist> = withContext(Dispatchers.IO) { withRetry { headers ->
        collectLibrary(headers, "Artists", ::parseArtistPage)
    } }

    private fun <T> collectLibrary(
        headers: Map<String, String>,
        filter: String,
        parse: (JsonObject) -> Triple<List<T>, Int, Int>,
    ): List<T> {
        val collected = mutableListOf<T>()
        var offset = 0
        while (true) {
            val (items, total, raw) = parse(libraryRoot(headers, filter, offset))
            collected += items
            offset += raw
            if (raw == 0 || offset >= total) break
        }
        return collected
    }

    private fun playlistPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> =
        parsePlaylistPage(libraryRoot(headers, "Playlists", offset))

    private fun libraryRoot(headers: Map<String, String>, filter: String, offset: Int): JsonObject {
        val variables = buildJsonObject {
            putJsonArray("filters") { add(filter) }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return gql("libraryV3", LIBRARY, variables, headers)
    }

    private fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", PLAYLIST, variables, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) { withRetry { headers ->
        if (playlistId == LIKED_ID) return@withRetry LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", PLAYLIST, variables, headers)
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    } }

    private fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", LIKED, variables, headers))
    }

    /**
     * Runs [block] with fresh headers, and tries again where trying again can help: once after a refused
     * token (a new one is minted), and with a growing wait after a rate limit or a dropped connection.
     */
    private suspend fun <T> withRetry(block: suspend (Map<String, String>) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(authHeaders())
            } catch (e: SpotifyException) {
                val again = when (e.kind) {
                    SpotifyException.Kind.EXPIRED -> attempt == 0
                    SpotifyException.Kind.RATE_LIMITED, SpotifyException.Kind.NETWORK -> attempt < 2
                    else -> false
                }
                if (!again) throw e
                val wait = when (e.kind) {
                    SpotifyException.Kind.RATE_LIMITED -> ((e.retryAfterSec ?: 2) * 1000L).coerceAtMost(8_000L)
                    SpotifyException.Kind.NETWORK -> 1_000L shl attempt
                    else -> 0L
                }
                if (wait > 0) kotlinx.coroutines.delay(wait)
                attempt++
            }
        }
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw SpotifyException(
                if (isSignedIn()) SpotifyException.Kind.EXPIRED else SpotifyException.Kind.SIGNED_OUT,
                if (isSignedIn()) "Spotify sign-in expired" else "Not signed in to Spotify",
            )
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
        }
    }

    private fun gql(operation: String, hash: String, variables: JsonObject, headers: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = try {
            Http.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> {
                            onTokenRejected()
                            throw SpotifyException(SpotifyException.Kind.EXPIRED, "Spotify refused the session (${response.code})")
                        }
                        429 -> throw SpotifyException(
                            SpotifyException.Kind.RATE_LIMITED, "Spotify asked to slow down",
                            retryAfterSec = response.header("Retry-After")?.toIntOrNull(),
                        )
                        else -> throw SpotifyException(SpotifyException.Kind.UNKNOWN, "Spotify $operation failed (${response.code})")
                    }
                }
                payload
            }
        } catch (e: java.io.IOException) {
            throw SpotifyException(SpotifyException.Kind.NETWORK, "Couldn't reach Spotify")
        }
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return root
    }
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The albums kept, the library's total, and how many entries the page held before filtering. */
internal fun parseAlbumPage(root: JsonObject): Triple<List<SpotifyAlbum>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        if (!wrapper.str("__typename").orEmpty().contains("Album", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyAlbum(
            id = uri.substringAfterLast(":"),
            name = name,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull {
                it.jsonObject.obj("profile")?.str("name")?.takeIf { n -> n.isNotBlank() }
            }.joinToString(", "),
            imageUrl = sourcesByWidth(data.obj("coverArt")?.arr("sources")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The artists followed, the library's total, and how many entries the page held before filtering. */
internal fun parseArtistPage(root: JsonObject): Triple<List<SpotifyArtist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        if (!wrapper.str("__typename").orEmpty().contains("Artist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val name = data.obj("profile")?.str("name") ?: data.str("name") ?: return@mapNotNull null
        if (name.isBlank()) return@mapNotNull null
        SpotifyArtist(
            id = uri.substringAfterLast(":"),
            name = name,
            imageUrl = sourcesByWidth(data.obj("visuals")?.obj("avatarImage")?.arr("sources")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The widest of a list of `{url, width}` sources. */
private fun sourcesByWidth(sources: JsonArray?): String? =
    sources?.mapNotNull { source ->
        val url = source.jsonObject.str("url") ?: return@mapNotNull null
        url to (source.jsonObject.int("width") ?: 0)
    }?.maxByOrNull { it.second }?.first

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
