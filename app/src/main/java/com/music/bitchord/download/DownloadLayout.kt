package com.music.bitchord.download

import java.util.Locale

/**
 * Where a downloaded track goes inside the download folder, from a template.
 *
 * A template is a path with `{tokens}`:
 *
 *  - `{albumArtist}` `{artist}` `{album}` `{title}` `{year}`
 *  - `{track}` and `{disc}`, zero-padded to two digits, empty when unknown
 *  - `{videoId}`
 *
 * so `{albumArtist}/{album}/{track} - {title}` gives
 * `Artist/Album/03 - Song.flac`. The same shape every library manager
 * (Picard, beets, MusicBee) produces by default, which is what makes the folder
 * readable by any other player without a database.
 *
 * Whatever a token turns into is made safe for a file name, and what is left
 * after an empty token is tidied up (`" - Song"` becomes `"Song"`), so one
 * template works for an album track, a single and a song with half its tags
 * missing.
 */
object DownloadLayout {
    const val DEFAULT_TEMPLATE = "{albumArtist}/{album}/{track} - {title}"

    /** The old flat naming, for anyone who wants their folder as it was. */
    const val FLAT_TEMPLATE = "{artist} - {title}"

    /** Named layouts offered in settings; anything else is a custom template. */
    val PRESETS = listOf(DEFAULT_TEMPLATE, "{album}/{track} - {title}", FLAT_TEMPLATE)

    /** What a template is filled from. Anything null is "not known", never an empty string. */
    data class Info(
        val title: String,
        val artist: String,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val track: Int? = null,
        val disc: Int? = null,
        val videoId: String = "",
    )

    const val SINGLES = "Singles"
    const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val MAX_SEGMENT = 100
    private const val MAX_NAME = 200

    /** A template that would produce nothing useful is replaced by the default. */
    fun valid(template: String): Boolean =
        TOKEN.findAll(template).all { it.groupValues[1] in TOKENS } &&
            (template.contains("{title}") || template.contains("{videoId}")) &&
            !template.startsWith("/") && !template.contains("..") && !template.endsWith("/")

    /**
     * The path below the download folder for this track, extension included,
     * with `/` between folders. [extension] has no dot.
     */
    fun relativeName(template: String, info: Info, extension: String): String {
        val use = if (valid(template)) template else DEFAULT_TEMPLATE
        val artist = info.artist.ifBlank { UNKNOWN_ARTIST }
        // A name is one path segment whatever it contains: "AC/DC" must not become two folders.
        val values = mapOf(
            // A track with no title still needs a name nobody else has: its id.
            "title" to info.title.ifBlank { info.videoId },
            "artist" to artist,
            "albumArtist" to (info.albumArtist?.takeIf { it.isNotBlank() } ?: artist),
            "album" to (info.album?.takeIf { it.isNotBlank() } ?: SINGLES),
            "year" to (info.year?.toString() ?: ""),
            "track" to pad(info.track),
            "disc" to pad(info.disc),
            "videoId" to info.videoId,
        ).mapValues { (_, v) -> v.replace('/', ' ') }
        fun fill(segment: String) =
            tidy(safe(TOKEN.replace(segment) { values[it.groupValues[1]].orEmpty() })).take(MAX_SEGMENT).trim()
        val parts = use.split('/')
        // Folders that come out empty are dropped; the file part never is, since a track must have a name.
        val folders = parts.dropLast(1).map(::fill).filter { it.isNotEmpty() }
        val stem = fill(parts.last()).ifEmpty { info.videoId.ifEmpty { "track" } }
        val name = (folders + "$stem.$extension").joinToString("/")
        return if (name.length <= MAX_NAME) name else (folders.take(2) + "${stem.take(MAX_SEGMENT)}.$extension").joinToString("/")
    }

    /** The folder part of [relativeName], without a trailing slash; empty for a flat layout. */
    fun folderOf(relativeName: String): String = relativeName.substringBeforeLast('/', "")

    fun fileNameOf(relativeName: String): String = relativeName.substringAfterLast('/')

    /** A track as the settings preview shows it. */
    fun preview(template: String): String = relativeName(
        template,
        Info(title = "Song Title", artist = "Artist", album = "Album Name", albumArtist = "Artist", year = 2021, track = 3, disc = 1),
        "flac",
    )

    private fun pad(n: Int?) = n?.takeIf { it > 0 }?.let { "%02d".format(it) } ?: ""

    /** Everything a FAT volume, the media store or a shell would object to. */
    private fun safe(raw: String) = raw.replace(ILLEGAL, " ").replace(WHITESPACE, " ")

    /** Drops the separators an empty token leaves at either end: `" - Song"` → `"Song"`. */
    private fun tidy(text: String): String =
        text.trim().trimStart(' ', '-', '_', '.', ',').trimEnd(' ', '-', '_', '.', ',').trim()

    private val TOKEN = Regex("\\{(\\w+)\\}")
    private val TOKENS = setOf("title", "artist", "albumArtist", "album", "year", "track", "disc", "videoId")
    private val ILLEGAL = Regex("""[\\:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Case-insensitive key, so two folders that differ only by case are one folder on every filesystem. */
    fun key(relativeName: String): String = relativeName.lowercase(Locale.ROOT)
}
