package com.music.bitchord.playback.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How hard the music is hitting right now, 0 to 1, for the player's backdrop to move with.
 *
 * Read off the audio the sink is about to play, at the end of [DspChain]: the bass (a one-pole low pass near
 * 150 Hz, where a kick and a bassline live) weighs most, the whole signal's level the rest, and both are
 * divided by a slowly falling peak so a quiet track moves its backdrop as far as a loud one. Costs one pass over
 * each block, and only while something is [active] — the player's backdrop asks for it while it is on screen
 * and the setting is on, and otherwise this is never called.
 *
 * Written on the audio thread and read on the UI's, as two volatile fields.
 */
object MusicLevel {
    @Volatile
    var active: Boolean = false

    @Volatile
    private var level: Float = 0f

    @Volatile
    private var stampNanos: Long = 0L

    private var lowPass = 0f
    private var peak = 0.05f

    /** The level as of the last block played, or 0 once the audio has stopped (a pause, a seek, the end). */
    fun read(): Float {
        if (System.nanoTime() - stampNanos > STALE_NANOS) return 0f
        return level
    }

    fun feed(block: AudioBlock, sampleRate: Int) {
        val frames = block.frameCount
        val channels = block.channelCount
        if (frames == 0 || sampleRate <= 0) return
        val samples = block.samples
        val a = (BASS_HZ * 2f * Math.PI.toFloat() / sampleRate).coerceIn(0.001f, 0.5f)
        var bass = 0.0
        var total = 0.0
        var lp = lowPass
        var i = 0
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until channels) mono += samples[i + c]
            mono /= channels
            i += channels
            lp += a * (mono - lp)
            bass += (lp * lp).toDouble()
            total += (mono * mono).toDouble()
        }
        lowPass = lp
        val bassRms = sqrt(bass / frames).toFloat()
        val totalRms = sqrt(total / frames).toFloat()
        val raw = BASS_WEIGHT * bassRms * BASS_BOOST + (1f - BASS_WEIGHT) * totalRms
        // A peak that falls over a few seconds: relative loudness, not absolute.
        peak = maxOf(raw, peak * PEAK_FALL).coerceAtLeast(PEAK_FLOOR)
        level = (raw / peak).coerceIn(0f, 1f)
        stampNanos = System.nanoTime()
    }

    private const val BASS_HZ = 150f
    private const val BASS_WEIGHT = 0.7f
    private const val BASS_BOOST = 1.8f
    private const val PEAK_FALL = 0.9985f
    private const val PEAK_FLOOR = 0.02f
    private const val STALE_NANOS = 250_000_000L
}
