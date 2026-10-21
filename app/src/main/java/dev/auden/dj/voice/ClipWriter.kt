package dev.auden.dj.voice

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Writes what the DJ says as a WAV file the player can queue, and the shape of its loudness. */
object ClipWriter {

    /** 16-bit mono PCM: what every Android player reads without a codec. */
    fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            fun int(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
            fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray()); int(36 + dataBytes)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1)
            int(sampleRate); int(sampleRate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(dataBytes)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                short(v)
            }
        }
    }

    /**
     * [bins] loudness values from 0 to 1, evenly spread over the clip: what the DJ's cover is drawn
     * from. RMS per bin, scaled so the loudest bin is 1, with a square root so quiet speech still
     * shows as movement rather than a flat line.
     */
    fun envelope(samples: FloatArray, bins: Int): FloatArray {
        if (samples.isEmpty() || bins <= 0) return FloatArray(0)
        val out = FloatArray(bins)
        val per = samples.size.toFloat() / bins
        for (b in 0 until bins) {
            val from = (b * per).toInt()
            val to = minOf(samples.size, ((b + 1) * per).toInt()).coerceAtLeast(from + 1)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            out[b] = sqrt(sum / (to - from)).toFloat()
        }
        val peak = out.maxOrNull()?.takeIf { it > 0f } ?: return out
        for (i in out.indices) out[i] = sqrt(abs(out[i] / peak))
        return out
    }
}
