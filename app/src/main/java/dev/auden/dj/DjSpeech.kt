package dev.auden.dj

import java.util.Random

/**
 * Shapes a line for speaking so it sounds said rather than read.
 *
 * Text-to-speech reads punctuation as instruction: a comma is a small breath, a full stop a longer
 * one, and nothing else gives it rhythm. So a line is split into its sentences, each is spoken as
 * its own utterance with a pause between them whose length depends on the punctuation, and the
 * speed and pitch of each wanders slightly, the way a person's does, instead of repeating exactly.
 */
object DjSpeech {

    data class Utterance(val text: String, val rate: Float, val pitch: Float, val pauseAfterMs: Long)

    fun shape(line: String, seed: Long): List<Utterance> {
        val rng = Random(seed)
        val sentences = line.trim()
            .replace("—", ", ").replace(" - ", ", ")
            .split(Regex("(?<=[.!?…:])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return sentences.mapIndexed { i, sentence ->
            val last = i == sentences.lastIndex
            Utterance(
                text = sentence,
                // A touch under the engine's default, which is brisk; and never the same twice.
                rate = (BASE_RATE + (rng.nextFloat() - 0.5f) * 0.06f),
                pitch = (BASE_PITCH + (rng.nextFloat() - 0.5f) * 0.05f),
                pauseAfterMs = if (last) 0L else when (sentence.last()) {
                    '?', '!' -> 380L
                    ':' -> 220L
                    else -> 320L
                } + rng.nextInt(90),
            )
        }
    }

    private const val BASE_RATE = 0.95f
    private const val BASE_PITCH = 1.0f
}
