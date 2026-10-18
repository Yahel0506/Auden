package dev.auden.library.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of a playlist file, whatever format it came from. */
data class PlaylistEntry(
    /** Where the file was said to be: absolute after parsing, as written after building. */
    val path: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Double? = null,
    val videoId: String? = null,
) {
    val isUrl: Boolean get() = path != null && URL_SCHEME.containsMatchIn(path)

    companion object {
        private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]+://")
    }
}

data class ParsedPlaylist(val name: String?, val entries: List<PlaylistEntry>)

/** Path helpers that behave the same on a phone and in a unit test. */
internal object Paths {
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayDeque<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeLast() else if (!absolute) out.addLast("..")
                else -> out.addLast(part)
            }
        }
        return (if (absolute) "/" else "") + out.joinToString("/")
    }

    fun join(dir: String, relative: String) = normalize("$dir/$relative")

    fun fileName(path: String) = path.replace('\\', '/').substringAfterLast('/')

    fun parent(path: String) = path.replace('\\', '/').substringBeforeLast('/', "")

    /** [path] written relative to [dir] (`../Other/x.mp3` when it lives beside it). */
    fun relativize(path: String, dir: String): String {
        val from = normalize(dir).trim('/').split('/').filter { it.isNotEmpty() }
        val to = normalize(path).trim('/').split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
        return (List(from.size - common) { ".." } + to.drop(common)).joinToString("/")
    }

    /** A name every storage layer accepts: reserved characters become underscores. */
    fun safeFileName(name: String): String =
        name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F\\x7F]"), "_").trim().trim('.').ifEmpty { "playlist" }

    /** [base] with " (2)", " (3)"… until [taken] stops objecting. */
    fun uniqueName(base: String, extension: String, taken: (String) -> Boolean): String {
        var candidate = "$base.$extension"
        var n = 2
        while (taken(candidate)) {
            candidate = "$base ($n).$extension"
            n++
        }
        return candidate
    }
}

/** `.m3u` / `.m3u8`: one path per line, with `#EXTINF` giving length and "Artist - Title". */
object M3u {
    fun write(name: String?, entries: List<PlaylistEntry>, baseDir: String?): String = buildString {
        append("#EXTM3U\n")
        if (name != null) append("#PLAYLIST:").append(name).append('\n')
        for (entry in entries) {
            val path = entry.path ?: continue
            val label = listOfNotNull(entry.artist, entry.title).joinToString(" - ")
            val seconds = entry.durationSec?.let { if (it > 0) it.toLong() else null } ?: -1
            append("#EXTINF:").append(seconds).append(',').append(label.replace('\n', ' ')).append('\n')
            append(if (baseDir != null && !entry.isUrl) Paths.relativize(path, baseDir) else path).append('\n')
        }
    }

    /** Relative lines are resolved against [baseDir], the folder the file was read from. */
    fun parse(text: String, baseDir: String?): ParsedPlaylist {
        var name: String? = null
        var info: String? = null
        val entries = mutableListOf<PlaylistEntry>()
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substringAfter(':').trim().ifEmpty { null }
                line.startsWith("#EXTINF:", ignoreCase = true) -> info = line.substringAfter(':')
                line.startsWith("#") -> Unit
                else -> {
                    val (seconds, artist, title) = parseInfo(info)
                    info = null
                    entries += PlaylistEntry(
                        path = resolve(line, baseDir),
                        title = title,
                        artist = artist,
                        durationSec = seconds,
                    )
                }
            }
        }
        return ParsedPlaylist(name, entries)
    }

    private fun parseInfo(info: String?): Triple<Double?, String?, String?> {
        if (info == null) return Triple(null, null, null)
        val seconds = info.substringBefore(',').trim().substringBefore(' ').toDoubleOrNull()?.takeIf { it > 0 }
        val label = info.substringAfter(',', "").trim()
        if (label.isEmpty()) return Triple(seconds, null, null)
        val split = label.indexOf(" - ")
        return if (split > 0) Triple(seconds, label.take(split).trim(), label.drop(split + 3).trim())
        else Triple(seconds, null, label)
    }

    private fun resolve(line: String, baseDir: String?): String {
        if (PlaylistEntry(path = line).isUrl && !line.startsWith("file://")) return line
        val local = java.net.URLDecoder.decode(line.removePrefix("file://").replace("+", "%2B"), "UTF-8").replace('\\', '/')
        return if (local.startsWith("/") || baseDir == null || Regex("^[A-Za-z]:/").containsMatchIn(local)) Paths.normalize(local)
        else Paths.join(baseDir, local)
    }
}

/** XSPF: XML playlists. Read with plain tag matching, never a DTD-aware parser. */
object Xspf {
    fun write(name: String?, entries: List<PlaylistEntry>, baseDir: String?): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n")
        if (name != null) append("  <title>").append(esc(name)).append("</title>\n")
        append("  <trackList>\n")
        for (entry in entries) {
            append("    <track>\n")
            entry.path?.let {
                val loc = if (baseDir != null && !entry.isUrl) Paths.relativize(it, baseDir) else it
                append("      <location>").append(esc(loc)).append("</location>\n")
            }
            entry.title?.let { append("      <title>").append(esc(it)).append("</title>\n") }
            entry.artist?.let { append("      <creator>").append(esc(it)).append("</creator>\n") }
            entry.album?.let { append("      <album>").append(esc(it)).append("</album>\n") }
            entry.durationSec?.let { append("      <duration>").append((it * 1000).toLong()).append("</duration>\n") }
            append("    </track>\n")
        }
        append("  </trackList>\n</playlist>\n")
    }

    fun parse(text: String, baseDir: String?): ParsedPlaylist {
        val body = text.removePrefix("﻿")
        val listStart = body.indexOf("<trackList", ignoreCase = true).takeIf { it >= 0 } ?: body.length
        val name = tag(body.substring(0, listStart), "title")
        val entries = Regex("<track>(.*?)</track>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(body).map { m ->
                val block = m.groupValues[1]
                val location = tag(block, "location")?.let { M3u.parse(it, baseDir).entries.firstOrNull()?.path }
                PlaylistEntry(
                    path = location,
                    title = tag(block, "title"),
                    artist = tag(block, "creator"),
                    album = tag(block, "album"),
                    durationSec = tag(block, "duration")?.toDoubleOrNull()?.div(1000.0)?.takeIf { it > 0 },
                )
            }.toList()
        return ParsedPlaylist(name, entries)
    }

    private fun tag(text: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.let(::unesc)?.trim()?.ifEmpty { null }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unesc(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** Auden's own format: lossless for streamed songs, which paths alone cannot describe. */
object AudenJson {
    @Serializable
    private data class Track(
        val videoId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationSec: Double? = null,
        val path: String? = null,
    )

    @Serializable
    private data class File(val auden: Int = 1, val name: String? = null, val tracks: List<Track> = emptyList())

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    fun write(name: String?, entries: List<PlaylistEntry>): String = json.encodeToString(
        File.serializer(),
        File(1, name, entries.map { Track(it.videoId, it.title, it.artist, it.album, it.durationSec, it.path) }),
    )

    fun parse(text: String): ParsedPlaylist? = runCatching {
        val file = json.decodeFromString(File.serializer(), text.removePrefix("﻿"))
        ParsedPlaylist(file.name, file.tracks.map { PlaylistEntry(it.path, it.title, it.artist, it.album, it.durationSec, it.videoId) })
    }.getOrNull()
}

/** Picks the reader for a file by its name, falling back to what the text looks like. */
object PlaylistFormats {
    fun parse(fileName: String, text: String, baseDir: String?): ParsedPlaylist? {
        val trimmed = text.removePrefix("﻿").trimStart()
        return when {
            trimmed.startsWith("{") -> AudenJson.parse(trimmed)
            trimmed.startsWith("<") || fileName.endsWith(".xspf", true) -> Xspf.parse(trimmed, baseDir)
            else -> M3u.parse(trimmed, baseDir)
        }
    }
}
