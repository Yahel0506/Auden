package dev.auden.dj.voice

/**
 * The offline voice the DJ speaks with: one English voice, whatever the app's language.
 *
 * English is the language whose open voices sound natural; the others sounded synthetic, so the DJ
 * speaks English everywhere and its lines are written in English too. The pack is a sherpa-onnx
 * Piper archive hosted as an asset of this repository's `dj-voices-1` release, downloaded once when
 * the DJ first speaks.
 */
object VoicePacks {
    /** Where the packs are hosted: the `dj-voices-1` release of this repository. */
    private const val BASE = "https://github.com/Yahel0506/Auden/releases/download/dj-voices-1/"

    class Pack(
        /** The app's language code this voice speaks ("es", "en", …). */
        val language: String,
        /** The archive's name without its extension; also the folder it unpacks into. */
        val id: String,
        /** Shown to the listener, with its quality. */
        val voice: String,
        val sizeBytes: Long,
        /** The licence the voice is published under; shown with the download. */
        val license: String,
    ) {
        val archiveName: String get() = "$id.tar.bz2"
        val url: String get() = BASE + archiveName
    }

    val all: List<Pack> = listOf(
        Pack("en", "vits-piper-en_US-libritts-high", "English (US) · high", 131_033_598, "CC BY 4.0"),
    )

    /** The DJ's voice; [language] is accepted so callers read the same, but every language gets English. */
    @Suppress("UNUSED_PARAMETER")
    fun forLanguage(language: String): Pack? = all.first()
}
