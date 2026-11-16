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
class DjClipRenderer(private val context: Context, private val voice: FishVoice = FishVoice()) {
    /** The names the voice is known to get wrong, and how to say them: bundled with the app. */
    private val lexicon: DjPronunciation.Lexicon by lazy {
        runCatching {
            context.assets.open("dj/pronunciations.tsv").bufferedReader().use { DjPronunciation.Lexicon.parse(it.readText()) }
        }.getOrDefault(DjPronunciation.Lexicon.EMPTY)
    }
    private val lookup by lazy { NameLookup(context) }

    /** How the dictionary's names are said, for the parser to hear them in what the listener asks. */
    val spokenAliases: Map<String, String> by lazy { lexicon.spokenToName() }

    private val dir = File(context.cacheDir, "dj/clips").apply { mkdirs() }
    private val mutex = Mutex()
    private var counter = 0L

    fun canSpeak(language: String): Boolean = voice.configured && FishVoice.voiceFor(language) != null

    /**
     * [names] are the artists the line may be about. The voice is given the line with those names respelled (see
     * [DjPronunciation]); the clip keeps the words as they were written, which are what the screen shows.
     */
    suspend fun render(language: String, line: String, seed: Long, names: Collection<String> = emptyList()): DjClip? {
        if (line.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                // A name in a script the voices cannot read is looked up (once, then remembered) for a readable one.
                val aliases = names.filter { DjPronunciation.needsAlias(it) && DjPronunciation.mentions(line, it) }
                    .associateWith { lookup.aliasOf(it) }
                val spoken = DjPronunciation.prepare(line, language, names, lexicon) { aliases[it] }
                val pcm = voice.say(language, spoken)
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
