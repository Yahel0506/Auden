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

    suspend fun render(language: String, line: String, seed: Long): DjClip? {
        if (line.isBlank()) return null
        val pack = VoicePacks.forLanguage(language)?.takeIf(VoicePackStore::isReady) ?: return null
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val voice = voiceFor(pack)
                val pieces = ArrayList<FloatArray>()
                for (part in DjSpeech.shape(line, seed)) {
                    pieces += voice.say(part.text, part.rate)
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
