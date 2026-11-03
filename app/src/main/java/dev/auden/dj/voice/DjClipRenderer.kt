package dev.auden.dj.voice

import android.content.Context
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
 * Turns a line into a clip, with the voice of the DJ's language.
 *
 * One line at a time, so the lines of a session reach the player in order. A line with no voice
 * behind it (no key, no network, a language with no voice) fails with the reason, and the caller
 * shows the words and plays on.
 */
class DjClipRenderer(context: Context, private val voice: FishVoice = FishVoice()) {
    private val dir = File(context.cacheDir, "dj/clips").apply { mkdirs() }
    private val mutex = Mutex()
    private var counter = 0L

    fun canSpeak(language: String): Boolean = voice.configured && FishVoice.voiceFor(language) != null

    suspend fun render(language: String, line: String, seed: Long): DjClip? {
        if (line.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val pcm = voice.say(language, line)
                val id = "clip-${seed.toString(36)}-${++counter}"
                val file = File(dir, "$id.wav")
                ClipWriter.writeWav(file, pcm, FishVoice.SAMPLE_RATE)
                check(file.length() > 44) { "the clip was not written" }
                DjClip(
                    id = id,
                    file = file,
                    durationMs = pcm.size * 1000L / FishVoice.SAMPLE_RATE,
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

    /** Nothing is held open between lines; kept so a session's teardown reads the same. */
    fun release() = Unit

    private companion object {
        const val ENVELOPE_BINS = 120
    }
}
