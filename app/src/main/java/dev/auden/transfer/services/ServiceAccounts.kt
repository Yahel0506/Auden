package dev.auden.transfer.services

import com.music.bitchord.data.Http
import dev.auden.library.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/*
 * Reading a music service's library the way its own web player does: the listener signs in on the service's page in
 * a WebView, the web player's bearer tokens are seen as it makes its requests, and the library is read with them.
 * Ported from Mochy Music's account importers (AccountModels, AccountHttp, AppleMusicAccount, TidalAccount),
 * reading through Auden's own HTTP client and handing the result on as a MusicSource.
 */

/** A service whose library is read from its web player's session. */
enum class AccountService(
    val service: Service,
    val label: String,
    /** Where sign-in starts. */
    val loginUrl: String,
    /** Sites whose cookies and storage belong to the service, cleared by "use a different account". */
    val webOrigins: List<String>,
    /** The web player that makes the requests the session is read from; only it is watched, never the sign-in pages. */
    val playerOrigins: Set<String>,
    /** Tidal's web player turns phones away, so it is opened as a desktop site. */
    val desktopSite: Boolean = false,
) {
    APPLE_MUSIC(
        service = Service.APPLE_MUSIC,
        label = "Apple Music",
        loginUrl = "https://music.apple.com/us/library/playlists",
        webOrigins = listOf("https://music.apple.com", "https://idmsa.apple.com", "https://authorize.music.apple.com", "https://www.apple.com"),
        playerOrigins = setOf("https://music.apple.com"),
    ),
    TIDAL(
        service = Service.TIDAL,
        label = "Tidal",
        loginUrl = "https://listen.tidal.com/login",
        webOrigins = listOf("https://listen.tidal.com", "https://login.tidal.com", "https://tidal.com"),
        playerOrigins = setOf("https://listen.tidal.com"),
        desktopSite = true,
    );

    companion object {
        fun of(service: Service): AccountService? = entries.firstOrNull { it.service == service }
    }
}

/** Desktop Chrome: Tidal's web player refuses phones. */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

/** The service refused or could not be read; [signedOut] says it wants the listener to sign in again. */
class AccountImportException(message: String, val signedOut: Boolean = false) : Exception(message)

/** The service asked to be left alone for a moment: try connecting again after [seconds]. */
class AccountRetryLater(val seconds: Long) : Exception()

/**
 * Session details seen while the listener signs in on the service's own page: the bearer tokens its web player sends,
 * and Apple's music-user token. Each is tried once against the API; tokens from before sign-in (anonymous) simply
 * fail that check.
 */
class CapturedCredentials {
    private val bearers = LinkedHashMap<String, String>()
    private val tried = HashSet<String>()

    @Volatile var appleUserToken: String? = null
        private set

    fun addHeader(name: String, value: String, url: String) {
        val trimmed = value.trim()
        when (name.trim().lowercase()) {
            "authorization" -> {
                if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return
                val token = trimmed.substring(7).trim()
                if (token.length < 20) return
                synchronized(this) {
                    bearers.remove(token)
                    bearers[token] = hostOf(url)
                    while (bearers.size > MAX_TOKENS) bearers.remove(bearers.keys.first())
                }
            }
            "media-user-token" -> if (trimmed.length >= 20) appleUserToken = trimmed
        }
    }

    /** Newest first; a token whose request had no readable host counts for every service. */
    fun untriedBearers(hostSuffix: String): List<String> = synchronized(this) {
        bearers.entries.filter { (token, host) -> token !in tried && (host.isEmpty() || host.endsWith(hostSuffix)) }
            .map { it.key }.asReversed()
    }

    fun newestBearer(hostSuffix: String): String? = synchronized(this) {
        bearers.entries.lastOrNull { (_, host) -> host.isEmpty() || host.endsWith(hostSuffix) }?.key
    }

    fun isTried(key: String): Boolean = synchronized(this) { key in tried }

    fun markTried(key: String) { synchronized(this) { tried += key } }

    fun clear() {
        synchronized(this) { bearers.clear(); tried.clear() }
        appleUserToken = null
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()

    private companion object { const val MAX_TOKENS = 24 }
}

/** A track as a service lists it, before it is matched to a song that plays. */
data class ServiceTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val imageUrl: String? = null,
)

data class ServicePlaylist(val id: String, val name: String, val subtitle: String = "", val coverUrl: String? = null)
data class ServiceAlbum(val id: String, val title: String, val artist: String, val coverUrl: String? = null)
data class ServiceArtist(val id: String, val name: String, val imageUrl: String? = null)

/** A signed-in account's library, read on demand. */
interface AccountLibrary {
    /** The songs the account likes (newest first), whole or not at all. */
    suspend fun likedTracks(): List<ServiceTrack>
    suspend fun playlists(): List<ServicePlaylist>
    suspend fun playlistTracks(id: String): List<ServiceTrack>
    suspend fun albums(): List<ServiceAlbum>
    suspend fun albumTracks(id: String): List<ServiceTrack>
    suspend fun artists(): List<ServiceArtist>
}

/** Turns captured credentials into a library once they belong to a signed-in account. */
interface AccountConnector {
    /** Null while nothing captured works yet. */
    suspend fun connect(credentials: CapturedCredentials): AccountLibrary?
}

/** JSON reads that tolerate what a web API leaves out. */
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.array(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.idString(key: String): String? = string(key)

internal object AccountHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class Response(val status: Int, val body: String, val retryAfterSeconds: Long?)

    suspend fun get(url: String, headers: Map<String, String>, service: String): Response = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            Http.client.newCall(request).execute().use { r ->
                Response(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.trim()?.toLongOrNull())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AccountImportException("Couldn't reach $service. Check your connection.")
        }
    }

    fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /**
     * A library read: waits out rate limits, and turns failures into messages a listener can act on. Null for a 404
     * when [notFoundAsNull], which some services use for an empty list.
     */
    suspend fun getJson(url: String, headers: Map<String, String>, service: String, notFoundAsNull: Boolean = false): JsonObject? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = get(url, headers, service)
            when {
                response.status in 200..299 ->
                    return parse(response.body) ?: throw AccountImportException("$service sent something we couldn't read.")
                response.status == 429 || response.status == 502 || response.status == 503 ->
                    delay((response.retryAfterSeconds ?: (2L shl attempt)).coerceIn(1, 30) * 1000)
                response.status == 401 -> throw AccountImportException("$service signed you out. Sign in again.", signedOut = true)
                response.status == 404 && notFoundAsNull -> return null
                response.status == 403 -> throw AccountImportException("$service won't share this one (403).")
                else -> throw AccountImportException("$service answered with an error (${response.status}).")
            }
        }
        throw AccountImportException("$service is busy right now. Try again in a minute.")
    }

    private const val MAX_ATTEMPTS = 5
}
