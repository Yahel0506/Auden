package dev.auden.dj

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * The DJ's voice: the system's speech engine, with the best voice it has and a line shaped to sound
 * said (see [DjSpeech]).
 *
 * Speech goes out on its own audio focus request, "transient, may duck", which the player — it
 * handles focus — answers by lowering the music for exactly as long as the DJ talks, and bringing
 * it back after. Nothing here touches the player's volume, so it cannot fight the crossfade.
 *
 * Created once and kept: binding to the engine is the slow part, a second or two, and happens
 * ahead of the first line rather than in the middle of a song.
 */
class DjVoice(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ids = AtomicLong()

    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var focus: AudioFocusRequest? = null

    @Volatile private var finishing: ((Boolean) -> Unit)? = null
    @Volatile private var lastId: String? = null

    /** Binds to the engine and picks the voice; true when it can speak [locale]. */
    suspend fun prepare(locale: Locale): Boolean = withContext(Dispatchers.Main) {
        val existing = ready
        if (existing != null) return@withContext existing.await()
        val deferred = CompletableDeferred<Boolean>().also { ready = it }
        engine = TextToSpeech(app) { status ->
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                deferred.complete(false)
                return@TextToSpeech
            }
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            val available = tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
            if (available) {
                tts.language = locale
                chooseVoice(tts, locale)
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == lastId) finishing?.invoke(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == lastId) finishing?.invoke(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == lastId) finishing?.invoke(false) }
            })
            deferred.complete(available)
        }
        deferred.await()
    }

    private fun chooseVoice(tts: TextToSpeech, locale: Locale) {
        val infos = tts.voices.orEmpty().associateBy {
            VoiceInfo(
                name = it.name,
                languageTag = it.locale.toLanguageTag(),
                quality = it.quality,
                latency = it.latency,
                requiresNetwork = it.isNetworkConnectionRequired,
                notInstalled = it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
            )
        }
        val online = AppSettings.meteredConnection.value != null
        val best = DjVoiceChooser.best(infos.keys, locale.toLanguageTag(), online) ?: return
        infos[best]?.let { tts.voice = it }
        Log.d(TAG, "voice ${best.name} (quality ${best.quality}, network ${best.requiresNetwork})")
    }

    /**
     * Says [line] and returns when it has been said, or at once with false if it cannot be. The
     * music ducks for the length of it.
     */
    suspend fun speak(line: String): Boolean {
        val tts = engine ?: return false
        if (ready?.await() != true || line.isBlank()) return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .build()
        focus = request
        audio.requestAudioFocus(request)
        return try {
            withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val stamp = ids.incrementAndGet()
                    val utterances = DjSpeech.shape(line, stamp)
                    finishing = { ok -> if (cont.isActive) cont.resume(ok) }
                    cont.invokeOnCancellation { runCatching { tts.stop() } }
                    utterances.forEachIndexed { i, u ->
                        tts.setSpeechRate(u.rate)
                        tts.setPitch(u.pitch)
                        val id = "dj-$stamp-$i"
                        val last = i == utterances.lastIndex
                        if (last) lastId = id
                        tts.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle.EMPTY, id)
                        if (!last && u.pauseAfterMs > 0) tts.playSilentUtterance(u.pauseAfterMs, TextToSpeech.QUEUE_ADD, "dj-$stamp-p$i")
                    }
                }
            } ?: false
        } finally {
            finishing = null
            audio.abandonAudioFocusRequest(request)
            focus = null
        }
    }

    fun stop() {
        engine?.stop()
        finishing?.invoke(false)
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = null
    }

    private companion object {
        const val TAG = "AudenDj"
        const val SPEAK_TIMEOUT_MS = 40_000L
    }
}
