package dev.auden.library.spotify

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.db.AudenDb
import dev.auden.library.db.SpotifyMatchEntity
import dev.auden.library.db.SpotifyMatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which YouTube Music song each Spotify track is.
 *
 * A match is looked up once and kept: opening the same playlist again, saving it to the library and syncing it
 * later all reuse it, so they are instant, work offline and always agree. One the listener corrected by hand is
 * never replaced by a later search. A track nothing matched is tried again after [RETRY_MISSING_MS], not on
 * every open.
 */
object SpotifyMatches {
    private const val RETRY_MISSING_MS = 3L * 24 * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
        val albumName: String? = null,
        val artistId: String? = null,
        val albumId: String? = null,
        val isVideo: Boolean = false,
        val isExplicit: Boolean? = null,
    )

    private fun Song.toStored() = Stored(videoId, title, artist, thumbnailUrl, durationText, albumName, artistId, albumId, isVideo, isExplicit)

    private fun Stored.toSong(spotifyId: String) = Song(
        videoId = videoId, title = title, artist = artist, thumbnailUrl = thumbnailUrl, durationText = durationText,
        albumName = albumName, artistId = artistId, albumId = albumId, isVideo = isVideo, isExplicit = isExplicit,
        spotifyId = spotifyId,
    )

    private fun dao(context: Context) = AudenDb.get(context).spotifyMatches()

    /** What is already known about [track], without asking anyone: the song, or null for a known miss or an unknown track. */
    suspend fun cached(context: Context, spotifyId: String): CachedMatch? = withContext(Dispatchers.IO) {
        val row = dao(context).get(spotifyId) ?: return@withContext null
        when {
            row.songJson != null -> CachedMatch.Found(json.decodeFromString<Stored>(row.songJson).toSong(spotifyId))
            System.currentTimeMillis() - row.matchedAt < RETRY_MISSING_MS -> CachedMatch.Missing
            else -> null
        }
    }

    sealed interface CachedMatch {
        data class Found(val song: Song) : CachedMatch
        data object Missing : CachedMatch
    }

    /** The song for [track]: remembered if known, else searched for and remembered. Null when there is none. */
    suspend fun resolve(context: Context, track: SpotifyTrack): Song? {
        when (val known = cached(context, track.id)) {
            is CachedMatch.Found -> return known.song
            CachedMatch.Missing -> return null
            null -> Unit
        }
        val match = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
            ?.let { it.copy(thumbnailUrl = it.thumbnailUrl ?: track.imageUrl, spotifyId = track.id) }
        withContext(Dispatchers.IO) {
            dao(context).put(
                SpotifyMatchEntity(
                    spotifyId = track.id,
                    status = if (match != null) SpotifyMatchStatus.AUTO else SpotifyMatchStatus.MISSING,
                    songJson = match?.let { json.encodeToString(it.toStored()) },
                ),
            )
        }
        return match
    }

    /** The listener chose [song] for [spotifyId]. */
    suspend fun setManual(context: Context, spotifyId: String, song: Song) = withContext(Dispatchers.IO) {
        dao(context).put(
            SpotifyMatchEntity(spotifyId, SpotifyMatchStatus.MANUAL, json.encodeToString(song.toStored())),
        )
    }
}
