package dev.auden.dj.voice

/**
 * The offline voices the DJ speaks with: one pack per app language, the best voice whose licence
 * allows the listener to keep and redistribute it.
 *
 * A pack is a sherpa-onnx Piper archive — the model, its phoneme tables and the speech data the
 * phonemiser needs — hosted as an asset of this repository's `dj-voices-1` release. The listener
 * downloads one only when the DJ first speaks in that language; each pack is only its own language,
 * so the download is never more than the voice in use.
 *
 * Languages without a pack are not spoken: the DJ still plays, and the line is shown on screen. That
 * is the honest choice over a system voice, which is what the listener heard before and disliked.
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
        Pack("es", "vits-piper-es_MX-claude-high", "Español (México) · high", 67_207_890, "Apache-2.0"),
        Pack("fr", "vits-piper-fr_FR-siwis-medium", "Français · medium", 67_207_459, "CC BY 4.0"),
        Pack("de", "vits-piper-de_DE-thorsten-high", "Deutsch · high", 115_591_546, "CC0"),
        Pack("pl", "vits-piper-pl_PL-bass-high", "Polski · high", 115_576_949, "Apache-2.0"),
        Pack("pt", "vits-piper-pt_BR-faber-medium", "Português (Brasil) · medium", 67_183_065, "CC0"),
        Pack("vi", "vits-piper-vi_VN-vais1000-medium", "Tiếng Việt · medium", 67_154_040, "CC BY 4.0"),
    )

    fun forLanguage(language: String): Pack? = all.firstOrNull { it.language == language }
}
