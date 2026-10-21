package dev.auden.dj

/** A voice the system's speech engine offers, reduced to what choosing needs. */
data class VoiceInfo(
    val name: String,
    /** BCP-47, e.g. "es-MX". */
    val languageTag: String,
    /** Android's `Voice.getQuality()`: 100 very low … 500 very high. */
    val quality: Int,
    /** Android's `Voice.getLatency()`: 100 very low … 500 very high (higher is slower). */
    val latency: Int,
    val requiresNetwork: Boolean,
    val notInstalled: Boolean = false,
)

/**
 * Picks the most natural-sounding voice the device has.
 *
 * The engine's own default is often the cheapest voice. What makes a voice sound like a person is
 * mostly the engine's neural models: Google's offline "-local" voices at high quality, and its
 * "-network" voices (the same neural family, streamed) when there is a connection to fetch them.
 * So: the right language first and the right region next, then installed over not installed, then
 * the highest declared quality, preferring a network voice only while online, and the quicker
 * voice when all else is level.
 */
object DjVoiceChooser {

    fun best(voices: Collection<VoiceInfo>, languageTag: String, online: Boolean): VoiceInfo? {
        val lang = languageTag.substringBefore('-').lowercase()
        val region = languageTag.substringAfter('-', "").uppercase()
        return voices
            .filter { it.languageTag.substringBefore('-').lowercase() == lang && !it.notInstalled }
            .filter { online || !it.requiresNetwork }
            .maxWithOrNull(
                compareBy<VoiceInfo> { if (region.isNotEmpty() && it.languageTag.substringAfter('-', "").uppercase() == region) 1 else 0 }
                    .thenBy { it.quality }
                    // Among equals, the streamed neural voice over the offline one while connected.
                    .thenBy { if (online && it.requiresNetwork) 1 else 0 }
                    .thenBy { -it.latency }
                    // Deterministic whatever order the engine lists them in.
                    .thenBy { it.name },
            )
    }
}
