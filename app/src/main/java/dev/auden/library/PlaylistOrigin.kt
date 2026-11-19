package dev.auden.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A service Auden can import from (and, on request, export to). */
enum class Service { YT_MUSIC, SPOTIFY }

/** What kind of remote list a playlist copy stands for. */
enum class OriginKind {
    PLAYLIST,
    ALBUM,
    /** The service's own list of liked songs: imported as likes, never as a playlist. */
    LIKES,
}

/**
 * Where a library playlist came from, read off its `sourceId` — the key [CloudSaver] has always saved copies
 * under — so nothing about it has to be stored twice or backfilled:
 *
 * - `spotify:playlist:<id>` is a Spotify playlist, and `spotify:playlist:liked` its Liked Songs;
 * - `spotify:album:<id>` a Spotify album;
 * - anything else is YouTube Music, by browse id without `VL`: `LM` is Liked Music, `MPREb_…` (and the
 *   `OLAK5uy_…` playlist behind an album) an album, the rest playlists.
 *
 * [remoteId] is the id on the service (`LM`, `PL…`, the Spotify id); [browseId] is the page that reads it.
 */
data class PlaylistOrigin(
    val service: Service,
    val kind: OriginKind,
    val remoteId: String,
    val sourceId: String,
) {
    val isLikesList: Boolean get() = kind == OriginKind.LIKES

    /** What [CloudSaver] and the YouTube Music client are asked for: a YT playlist with its `VL`. */
    val browseId: String
        get() = when {
            service == Service.SPOTIFY -> sourceId
            kind == OriginKind.ALBUM && remoteId.startsWith(YT_ALBUM) -> remoteId
            else -> "VL$remoteId"
        }

    companion object {
        const val SPOTIFY_PLAYLIST = "spotify:playlist:"
        const val SPOTIFY_ALBUM = "spotify:album:"
        private const val YT_ALBUM = "MPREb"
        private const val YT_ALBUM_PLAYLIST = "OLAK5uy"

        /** Null for a playlist made here (no source), or a source id of no known shape. */
        fun of(sourceId: String?): PlaylistOrigin? {
            val source = sourceId?.trim().orEmpty()
            if (source.isEmpty()) return null
            return when {
                source.startsWith(SPOTIFY_PLAYLIST) -> {
                    val id = source.removePrefix(SPOTIFY_PLAYLIST).takeIf { it.isNotEmpty() } ?: return null
                    val kind = if (LikeListClassifier.isLikesList(Service.SPOTIFY, id)) OriginKind.LIKES else OriginKind.PLAYLIST
                    PlaylistOrigin(Service.SPOTIFY, kind, id, source)
                }
                source.startsWith(SPOTIFY_ALBUM) -> {
                    val id = source.removePrefix(SPOTIFY_ALBUM).takeIf { it.isNotEmpty() } ?: return null
                    PlaylistOrigin(Service.SPOTIFY, OriginKind.ALBUM, id, source)
                }
                // Any other "scheme:" is not a YouTube Music browse id (those never hold a colon).
                ':' in source -> null
                else -> {
                    val id = source.removePrefix("VL").takeIf { it.isNotEmpty() } ?: return null
                    val kind = when {
                        LikeListClassifier.isLikesList(Service.YT_MUSIC, id) -> OriginKind.LIKES
                        id.startsWith(YT_ALBUM) || id.startsWith(YT_ALBUM_PLAYLIST) -> OriginKind.ALBUM
                        else -> OriginKind.PLAYLIST
                    }
                    // Stored without the VL, as CloudSaver.sourceId has always written it.
                    PlaylistOrigin(Service.YT_MUSIC, kind, id, id)
                }
            }
        }
    }
}

/**
 * Whether a remote list is the service's liked songs. Those are the likes, not a playlist: an import takes them
 * into Auden's likes and a list of importable playlists leaves them out.
 */
object LikeListClassifier {
    fun isLikesList(service: Service, id: String?): Boolean {
        val raw = id?.trim().orEmpty()
        return when (service) {
            Service.YT_MUSIC -> raw.removePrefix("VL") == "LM"
            Service.SPOTIFY -> raw.removePrefix(PlaylistOrigin.SPOTIFY_PLAYLIST) == "liked"
        }
    }
}

/**
 * The source's listing at the last import or refresh, as kept in `playlist_origin.snapshot`: the remote ids in
 * the source's order (YouTube Music video ids, Spotify track ids), as a JSON array.
 */
object PlaylistSnapshot {
    private val codec = ListSerializer(String.serializer())

    fun encode(ids: List<String>): String = Json.encodeToString(codec, ids)

    /** Null for no snapshot, and for one that cannot be read — which a refresh must treat the same way. */
    fun decode(text: String?): List<String>? =
        text?.let { runCatching { Json.decodeFromString(codec, it) }.getOrNull() }
}

/**
 * What an import or a save records about the source alongside a new copy (see `playlist_origin`). [snapshot]
 * null: the source named no ids it could be compared by. [syncedAt] null: the source was not read just now (a
 * copy rebuilt from what is on the device).
 */
data class ImportedOrigin(
    val snapshot: List<String>?,
    val syncedAt: Long?,
    val originTitle: String?,
)
