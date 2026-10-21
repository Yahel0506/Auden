package dev.auden.dj.voice

import android.content.Context
import dev.auden.dj.DjSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One line the DJ says, rendered: the file to queue, its length, its loudness and its words. */
class DjClip(
    val id: String,
    val file: File,
    val durationMs: Long,
    val envelope: FloatArray,
    val text: String,
)

/**
 * Turns a line into a clip, with the voice of the app's language.
 *
 * Renders one line at a time (the voice is not re-entrant), keeps the voice loaded while the
 * language stays the same, and releases it when the language changes. A line in a language with no
 * installed voice renders to nothing: the caller shows the words and plays on.
 */
class DjClipRenderer(context: Context) {
    private val dir = File(context.cacheDir, "dj/clips").apply { mkdirs() }
    private val mutex = Mutex()
    private var loaded: Pair<String, SherpaVoice>? = null
    private var counter = 0L

    fun canSpeak(language: String): Boolean =
        VoicePacks.forLanguage(language)?.let(VoicePackStore::isReady) == true

    /**
     * [englishNames] are titles and artists that should sound English inside a line in another
     * language; they are spoken by the English voice when it is installed, and by the line's own
     * voice otherwise.
     */
    suspend fun render(language: String, line: String, seed: Long, englishNames: List<String> = emptyList()): DjClip? {
        if (line.isBlank()) return null
        val pack = VoicePacks.forLanguage(language)?.takeIf(VoicePackStore::isReady) ?: return null
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val voice = voiceFor(pack)
                val english = if (language == "en" || englishNames.isEmpty()) null else englishVoice()
                val pieces = ArrayList<FloatArray>()
                for (part in DjSpeech.shape(line, seed)) {
                    if (english == null) {
                        pieces += voice.say(part.text, part.rate)
                    } else {
                        for ((text, isEnglish) in DjSpeech.splitNames(part.text, englishNames)) {
                            if (isEnglish) pieces += resample(english.say(text, part.rate), english.sampleRate, voice.sampleRate)
                            else if (text.any(Char::isLetterOrDigit)) pieces += voice.say(text, part.rate)
                        }
                    }
                    if (part.pauseAfterMs > 0) pieces += FloatArray((voice.sampleRate * part.pauseAfterMs / 1000).toInt())
                }
                val pcm = concat(pieces)
                val id = "clip-${seed.toString(36)}-${++counter}"
                val file = File(dir, "$id.wav")
                check(pcm.isNotEmpty()) { "the voice produced no sound" }
                ClipWriter.writeWav(file, pcm, voice.sampleRate)
                check(file.length() > 44) { "the clip was not written" }
                DjClip(
                    id = id,
                    file = file,
                    durationMs = pcm.size * 1000L / voice.sampleRate,
                    envelope = ClipWriter.envelope(pcm, ENVELOPE_BINS),
                    text = line,
                )
            }
        }
    }

    /** Deletes every rendered clip; called when a session ends. */
    fun clearClips() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun release() {
        loaded?.second?.close()
        loaded = null
        englishLoaded?.close()
        englishLoaded = null
    }

    private var englishLoaded: SherpaVoice? = null

    /** The English voice, if the listener has it installed; names stay in the line's voice without it. */
    private fun englishVoice(): SherpaVoice? {
        englishLoaded?.let { return it }
        val pack = VoicePacks.forLanguage("en")?.takeIf(VoicePackStore::isReady) ?: return null
        return runCatching { SherpaVoice.open(VoicePackStore.folderOf(pack)) }.getOrNull()?.also { englishLoaded = it }
    }

    private fun resample(pcm: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || pcm.isEmpty()) return pcm
        val out = FloatArray((pcm.size.toLong() * to / from).toInt())
        for (i in out.indices) {
            val at = i.toDouble() * from / to
            val a = at.toInt().coerceAtMost(pcm.lastIndex)
            val b = (a + 1).coerceAtMost(pcm.lastIndex)
            out[i] = pcm[a] + (pcm[b] - pcm[a]) * (at - a).toFloat()
        }
        return out
    }

    private fun voiceFor(pack: VoicePacks.Pack): SherpaVoice {
        loaded?.takeIf { it.first == pack.id }?.let { return it.second }
        loaded?.second?.close()
        val voice = SherpaVoice.open(VoicePackStore.folderOf(pack))
        loaded = pack.id to voice
        return voice
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    private companion object {
        const val ENVELOPE_BINS = 120
    }
}
