package dev.auden.dj

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Asks a language model, through the service in `web/dj`, what a listener's ask means, and gets a [DjPlan] back.
 *
 * What the model is given is only the ask and a summary of the listener's taste (their most played artists and genres,
 * the names of their playlists), so that "el de Prey" or "mi playlist de gym" can be resolved. It never sees songs
 * and never chooses any. With no service configured, offline, or when it is slow, the answer is null and the keyword
 * reader ([DjRequestParser]) understands the ask alone, as it always has.
 */
object DjInterpreter {
    /** What the model is told about the listener. */
    @kotlinx.serialization.Serializable
    class Context(val artists: List<String>, val genres: List<String>, val playlists: List<String>, val language: String)

    @kotlinx.serialization.Serializable
    private class Body(val text: String, val context: Context)

    private val json = Json { encodeDefaults = true }
    private val media = "application/json".toMediaType()
    private val base: String get() = BuildConfig.DJ_INTERPRETER_URL

    private val secret: String get() = BuildConfig.DJ_APP_SECRET

    /** Both the service's address and the secret its requests are signed with: a build without either never asks. */
    val configured: Boolean get() = base.isNotBlank() && secret.isNotBlank()

    /** HMAC-SHA256 of "timestamp.body" under the app's secret, as hex: what the Worker checks to know the app sent it. */
    internal fun sign(secret: String, timestamp: Long, body: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun interpret(text: String, context: Context): DjPlan? {
        if (!configured || text.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = json.encodeToString(Body(text.trim().take(MAX_ASK), context))
                val now = System.currentTimeMillis() / 1000
                val request = Request.Builder()
                    .url("$base/interpret")
                    .header("x-auden-ts", now.toString())
                    .header("x-auden-sig", sign(secret, now, payload))
                    .post(payload.toRequestBody(media))
                    .build()
                val client = Http.client.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()?.let(DjPlan::parse)
                }
            }.getOrNull()
        }
    }

    /** The taste summary the model is given: [DjTaste.profile]'s artists and genres, and the playlists of the pool. */
    fun contextFor(pool: Collection<DjTrack>, genresOf: (String) -> List<String>, language: String): Context {
        val profile = DjTaste.profile(pool, genresOf, maxArtists = 30, maxGenres = 12)
        val playlists = pool.asSequence().flatMap { it.playlists }.distinct().take(25).toList()
        return Context(profile.artists, profile.genres, playlists, language)
    }

    private const val MAX_ASK = 300
    private const val CALL_TIMEOUT_MS = 4_500L
}
