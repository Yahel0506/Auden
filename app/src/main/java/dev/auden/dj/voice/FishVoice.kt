package dev.auden.dj.voice

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The DJ's voice: Fish Audio's text-to-speech, one request per line.
 *
 * Two voices, one per language the DJ speaks. The line goes up as text and comes back as raw 16-bit
 * mono PCM, which [ClipWriter] turns into the WAV the player queues. PCM rather than WAV because the
 * response is streamed, and a streamed WAV header cannot know its own length.
 */
class FishVoice(
    private val apiKey: String = BuildConfig.FISH_API_KEY,
    private val client: OkHttpClient = HTTP,
    private val endpoint: String = ENDPOINT,
) {
    /** A voice from Fish Audio's library: [name] is shown, [referenceId] is what the API takes. */
    class Voice(val name: String, val referenceId: String)

    /** Why a line was not spoken; [message] is short enough to show. */
    class FishException(message: String, cause: Throwable? = null) : IOException(message, cause)

    val configured: Boolean get() = apiKey.isNotBlank()

    /** The PCM of [text] in [language], as samples from -1 to 1 at [SAMPLE_RATE]. */
    fun say(language: String, text: String): FloatArray {
        if (!configured) throw FishException("no_key")
        val voice = voiceFor(language) ?: throw FishException("no_voice")
        val request = request(text, language, voice, apiKey, endpoint)
        val bytes = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw FishException(describe(response.code))
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: FishException) {
            throw e
        } catch (e: IOException) {
            throw FishException("network", e)
        }
        val samples = decode(bytes)
        if (samples.isEmpty()) throw FishException("empty")
        return samples
    }

    companion object {
        const val SAMPLE_RATE = 24_000

        /**
         * The free tier. An absent or unknown `model` header falls back to the paid model, so the
         * exact string is what keeps this from billing.
         */
        const val MODEL = "s2.1-pro-free"
        const val ENDPOINT = "https://api.fish.audio/v1/tts"

        val CARA = Voice("DJ Cara", "3de8512d76484ddb8f91593c4cfbbc42")
        val WENDY = Voice("Wendy", "1060f143d4444d9b8cc8ab45e7f6520e")

        /** The languages the DJ speaks, in the order the setting offers them. */
        val LANGUAGES = listOf("es", "en")

        fun voiceFor(language: String): Voice? = when (language) {
            "en" -> CARA
            "es" -> WENDY
            else -> null
        }

        // A POST must not be sent twice behind our back (it is billed per call), and a line that takes
        // longer than this is not worth the wait: the DJ plays on with the words on screen.
        private val HTTP: OkHttpClient by lazy {
            Http.client.newBuilder()
                .retryOnConnectionFailure(false)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        }

        fun request(text: String, language: String, voice: Voice, apiKey: String, endpoint: String = ENDPOINT): Request {
            val body = buildJsonObject {
                put("text", text)
                put("reference_id", voice.referenceId)
                put("format", "pcm")
                put("sample_rate", SAMPLE_RATE)
                put("normalize", true)
                put("latency", "balanced")
                put("language", language)
            }.toString().toRequestBody("application/json".toMediaType())
            return Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("model", MODEL)
                .post(body)
                .build()
        }

        /** 16-bit little-endian mono PCM to samples; a stray last byte is half a sample and is dropped. */
        fun decode(bytes: ByteArray): FloatArray {
            val n = bytes.size / 2
            val out = FloatArray(n)
            for (i in 0 until n) {
                val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                out[i] = v.toShort() / 32768f
            }
            return out
        }

        private fun describe(code: Int) = when (code) {
            401 -> "key_rejected"
            402 -> "no_credit"
            429 -> "rate_limited"
            503 -> "busy"
            else -> "http_$code"
        }
    }
}
