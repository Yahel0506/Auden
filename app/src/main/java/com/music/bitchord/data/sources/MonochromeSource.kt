package com.music.bitchord.data.sources

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BitChord"

/**
 * Where [MonochromeSource] streams from: the one URL the official release packages in.
 *
 * It lives in the build, not in the source tree — see `MONOCHROME_SERVER` in the app's build
 * script — so a build made without it has no such source, and nothing here can be pointed at
 * anywhere else. [override] is for tests only.
 */
internal object MonochromeServer {
    @Volatile
    internal var override: String? = null

    val url: String?
        get() = (override ?: BuildConfig.MONOCHROME_SERVER).trim().trimEnd('/').takeIf { it.isNotEmpty() }

    val isAvailable: Boolean get() = url != null

    /** Tells the HTTP client to wait out this server's 429s rather than fail on them; called at startup. */
    fun register() {
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.let { Http.politeHosts.add(it) }
    }
}

/**
 * Lossless and high-bitrate streams from the release's Monochrome server.
 *
 * Two plain GETs: `/search/tracks?q=` for the catalogue and `/track/{id}` for a ranged file.
 * Nothing here asks the server for a particular tier — it hands over whichever rendition it
 * holds — so what the file turned out to be is read off its first bytes and its size, and the
 * request decides what that is worth: a FLAC or a high-bitrate AAC both answer a lossless ask
 * (the resolver marks the AAC as below the request and keeps it as the floor), and on the High
 * rung a FLAC heavier than CD quality is a miss rather than a 90 MB track.
 */
class MonochromeSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MONOCHROME
    override val displayName: String get() = config.label.ifBlank { SourceKind.MONOCHROME.label }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Runtimes the catalogue gave, by track id: the file's bitrate is its size over this. */
    private val durations = ConcurrentHashMap<String, Int>()

    /**
     * When a server that keeps failing may be asked again, so a dead one costs a song a wait, not
     * every song. Set only after [FAILURES_BEFORE_QUIET] failures in a row: one slow or reset answer
     * from a busy server must not shut every following lookup (and download) out for minutes.
     */
    @Volatile
    private var quietUntilMs = 0L
    @Volatile
    private var failures = 0

    private fun failed() {
        if (++failures >= FAILURES_BEFORE_QUIET) quietUntilMs = System.currentTimeMillis() + QUIET_MS
    }

    private fun answered() {
        failures = 0
        quietUntilMs = 0L
    }

    /** [block], once more after a short pause when the first try broke on the connection. */
    private suspend fun <T> twice(block: suspend () -> T): T = try {
        block()
    } catch (e: java.io.IOException) {
        kotlinx.coroutines.delay(RETRY_PAUSE_MS)
        block()
    }

    override suspend fun health(): SourceHealth {
        val base = MonochromeServer.url ?: return SourceHealth.Unreachable("Not available in this build")
        return try {
            val response = get("$base/search/tracks?q=test&limit=1")
            if (response.first in 200..299) {
                quietUntilMs = 0L
                SourceHealth.Ok()
            } else {
                SourceHealth.Unreachable("The server answered ${response.first}")
            }
        } catch (e: Exception) {
            SourceHealth.Unreachable("The server didn't answer")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val base = MonochromeServer.url ?: return emptyList()
        if (System.currentTimeMillis() < quietUntilMs) return emptyList()
        val (code, body) = try {
            twice { get("$base/search/tracks?q=${enc(query)}&limit=$limit") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed()
            TrackLog.w(TAG, "Monochrome didn't answer: ${e.message}")
            return emptyList()
        }
        if (code !in 200..299) {
            if (code >= 500) failed()
            return emptyList()
        }
        answered()
        return rowsIn(body).take(limit).map { row ->
            row.durationSec?.let { if (durations.size < MAX_DURATIONS) durations[row.id] = it }
            Song(
                videoId = SourceRegistry.trackKey(config.id, row.id),
                title = row.title,
                artist = row.artist,
                thumbnailUrl = row.artwork,
                durationText = row.durationSec?.let { String.format("%d:%02d", it / 60, it % 60) },
                isExplicit = row.explicit,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        // The server has one rendition per track and cannot be capped to a bitrate.
        if (request is StreamRequest.Capped) return null
        val base = MonochromeServer.url ?: return null
        if (System.currentTimeMillis() < quietUntilMs) return null
        val url = "$base/track/${enc(trackId)}"
        val head = try {
            twice { probe(url) }.also { answered() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed()
            TrackLog.w(TAG, "Monochrome had no stream for $trackId: ${e.message}")
            return null
        } ?: return null
        val durationSec = durations[trackId]
        val format = formatOf(head.bytes, head.totalBytes, durationSec) ?: return null
        // The High rung is the small file: a lossless one only while it is CD quality or less.
        if (request is StreamRequest.Best && !SourceResolver.fitsHighRung(format)) {
            TrackLog.d(TAG, "Monochrome's ${format.summary} is more than the High rung keeps; passing")
            return null
        }
        return SourceStream(
            url = url,
            format = format,
            headers = mapOf("User-Agent" to USER_AGENT),
            durationSec = durationSec,
        )
    }

    // ---- network -----------------------------------------------------------------------------

    private suspend fun get(url: String): Pair<Int, String> = gate.withPermit {
        var attempt = 0
        while (true) {
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
                Http.client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
            }
            // A server that says it is being asked too much is waited out, not given up on: an album
            // of downloads asks for a dozen lookups at once, and each of them answering "nothing"
            // sent the whole album to YouTube.
            if (result.first != 429 || ++attempt > BUSY_RETRIES) {
                if (result.first !in 200..299) TrackLog.w(TAG, "Monochrome answered ${result.first} to a search")
                return@withPermit result
            }
            kotlinx.coroutines.delay(BUSY_PAUSE_MS * attempt)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private class Head(val bytes: ByteArray, val totalBytes: Long?)

    /** What a probe hands back when the server said 429, apart from a refusal or a file. */
    private val BUSY = Any()

    /** At most this many lookups at once against the server, however many songs ask together. */
    private val gate = kotlinx.coroutines.sync.Semaphore(LOOKUPS_AT_ONCE)

    /** The file's first bytes and its size, from one ranged GET; null when what answered is not audio. */
    private suspend fun probe(url: String): Head? = gate.withPermit {
        var attempt = 0
        while (true) {
            val outcome = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-${HEAD_BYTES - 1}")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (response.code == 429) return@use BUSY
                    if (!response.isSuccessful) {
                        TrackLog.w(TAG, "Monochrome answered ${response.code} for a track")
                        return@use null
                    }
                    val bytes = response.body?.byteStream()?.use { it.readNBytes(HEAD_BYTES) } ?: return@use null
                    val total = when (response.code) {
                        206 -> response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        else -> response.header("Content-Length")?.toLongOrNull()
                    }
                    if (!looksLikeAudio(bytes)) null else Head(bytes, total)
                }
            }
            if (outcome !== BUSY) return@withPermit outcome as Head?
            if (++attempt > BUSY_RETRIES) {
                TrackLog.w(TAG, "Monochrome stayed busy for a track; giving up on it")
                return@withPermit null
            }
            kotlinx.coroutines.delay(BUSY_PAUSE_MS * attempt)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    // ---- parsing -----------------------------------------------------------------------------

    internal class Row(
        val id: String,
        val title: String,
        val artist: String,
        val artwork: String?,
        val durationSec: Int?,
        val explicit: Boolean?,
    )

    internal fun rowsIn(text: String): List<Row> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val tracks = root["tracks"] as? JsonArray ?: return emptyList()
        return tracks.mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            if ((t["playable"] as? JsonPrimitive)?.booleanOrNull == false) return@mapNotNull null
            val id = t.string("trackId") ?: t.string("id") ?: return@mapNotNull null
            val title = t.string("title") ?: return@mapNotNull null
            val artists = (t["artistNames"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            // Milliseconds from the current server; older answers gave seconds.
            val seconds = (t["duration"] as? JsonPrimitive)?.longOrNull?.let { if (it > 10_000) it / 1000 else it }?.toInt()
            Row(
                id = id,
                title = title,
                artist = artists.joinToString(", "),
                artwork = t.string("artwork"),
                durationSec = seconds,
                explicit = (t["explicit"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    internal companion object {
        private const val QUIET_MS = 20L * 1000
        private const val FAILURES_BEFORE_QUIET = 3
        private const val RETRY_PAUSE_MS = 700L
        private const val LOOKUPS_AT_ONCE = 2
        private const val BUSY_RETRIES = 3
        private const val BUSY_PAUSE_MS = 2_000L
        private const val HEAD_BYTES = 42
        private const val MAX_DURATIONS = 2_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        /** Above this an MP4 is more likely a lossless one than an AAC, and its codec is left unstated. */
        private const val MAX_AAC_KBPS = 700

        /** Whether the first bytes are an audio file at all, rather than a JSON error or a web page. */
        internal fun looksLikeAudio(head: ByteArray): Boolean {
            if (head.size < 8) return false
            val text = head.copyOfRange(0, 8).decodeToString()
            return text.startsWith("fLaC") || text.startsWith("OggS") || text.startsWith("ID3") ||
                text.substring(4, 8) == "ftyp" || (head[0].toInt() and 0xff == 0xff && head[1].toInt() and 0xe0 == 0xe0)
        }

        /**
         * What the file is, from its head and its size: a FLAC says its depth and rate in its
         * STREAMINFO; anything else is judged by its average bitrate, the file over the runtime
         * the catalogue gave, and marked inferred since nothing stated it.
         */
        internal fun formatOf(head: ByteArray, totalBytes: Long?, durationSec: Int?): StreamFormat? {
            if (!looksLikeAudio(head)) return null
            flacInfo(head)?.let { (bits, rate) ->
                return StreamFormat(codec = "flac", sampleRateHz = rate, bitDepth = bits)
            }
            val kbps = if (totalBytes != null && durationSec != null && durationSec > 0) {
                (totalBytes * 8 / 1000 / durationSec).toInt().takeIf { it > 0 }
            } else {
                null
            }
            return when {
                kbps == null -> StreamFormat(codec = "aac")
                kbps > MAX_AAC_KBPS -> StreamFormat(kbps = null)
                else -> StreamFormat(codec = "aac", kbps = kbps, kbpsInferred = true)
            }
        }

        /** "fLaC", then STREAMINFO: after the block header and 10 bytes of sizes, 20 bits of rate, 3 of channels, 5 of depth. */
        internal fun flacInfo(bytes: ByteArray): Pair<Int, Int>? {
            if (bytes.size < 4 || bytes.copyOfRange(0, 4).decodeToString() != "fLaC") return null
            if (bytes.size < 26) return 16 to 44_100
            val b = bytes.copyOfRange(8, 26).map { it.toInt() and 0xff }
            val rate = (b[10] shl 12) or (b[11] shl 4) or (b[12] shr 4)
            val bits = (((b[12] and 0x01) shl 4) or (b[13] shr 4)) + 1
            return if (rate in 8_000..768_000 && bits in 8..32) bits to rate else 16 to 44_100
        }
    }
}
