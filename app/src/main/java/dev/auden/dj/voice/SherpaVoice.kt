package dev.auden.dj.voice

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * One Piper voice, running on the phone through sherpa-onnx.
 *
 * Synthesis is not thread-safe, so [say] is synchronised; the DJ renders one line at a time on a
 * worker, and a line of a few sentences takes well under the length of the song it precedes.
 */
class SherpaVoice private constructor(private val tts: OfflineTts) : AutoCloseable {

    val sampleRate: Int = tts.sampleRate()

    /** The speech of [text] as mono floats in [-1, 1], at [sampleRate]. [speed] 1 is the voice's own pace. */
    @Synchronized
    fun say(text: String, speed: Float): FloatArray = tts.generate(text, SPEAKER, speed).samples

    @Synchronized
    override fun close() = tts.release()

    companion object {
        /** The first speaker of a multi-speaker voice; single-speaker voices have only this one. */
        private const val SPEAKER = 0

        fun open(folder: File): SherpaVoice {
            val model = checkNotNull(folder.listFiles { f -> f.name.endsWith(".onnx") }?.singleOrNull()) {
                "no single voice model in ${folder.name}"
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.path,
                        tokens = File(folder, "tokens.txt").path,
                        dataDir = File(folder, "espeak-ng-data").path,
                        // The voice's own defaults for expressiveness; sherpa's are the Piper authors'.
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1f,
                    ),
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
                maxNumSentences = 1,
            )
            return SherpaVoice(OfflineTts(null, config))
        }
    }
}
