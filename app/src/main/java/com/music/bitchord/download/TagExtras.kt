package com.music.bitchord.download

/**
 * The tags beyond title, artist, album and lyrics that a library manager reads:
 * who the album is by, where the track sits in it, when it came out, and a note
 * of where it came from.
 *
 * Optional everywhere it is accepted: a tagger given none writes exactly what it
 * always did.
 */
data class TagExtras(
    val albumArtist: String? = null,
    val track: Int? = null,
    val trackTotal: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    /** Free text; downloads put the song's YouTube Music link here, so the file can be recognised later. */
    val comment: String? = null,
) {
    val isEmpty: Boolean
        get() = albumArtist.isNullOrBlank() && track == null && disc == null && year == null && comment.isNullOrBlank()

    /** `1` or `1/12`, the way every tag format writes a position. */
    fun trackText(): String? = track?.takeIf { it > 0 }?.let { if (trackTotal != null && trackTotal > 0) "$it/$trackTotal" else "$it" }
}
