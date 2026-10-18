package dev.auden.library.links

import com.music.bitchord.data.model.Song
import dev.auden.library.SongIdentity
import dev.auden.library.db.TrackEntity
import dev.auden.library.io.PlaylistMatcher

/**
 * The tracks on this device, looked up by what they are rather than where they
 * are: the same question [SongIdentity] answers for two rows, asked of every
 * file at once.
 *
 * Keyed by normalised title so a lookup only compares the handful of files
 * that share one, instead of every file in the library.
 */
class LocalMatchIndex(tracks: List<TrackEntity>) {
    private val byTitle: Map<String, List<TrackEntity>> = tracks
        .filter { it.localUri != null }
        .groupBy { PlaylistMatcher.normalize(it.title) }
        .filterKeys { it.isNotEmpty() }

    private val byVideoId: Map<String, List<TrackEntity>> = tracks
        .filter { it.localUri != null && !it.videoId.isNullOrBlank() }
        .groupBy { it.videoId!! }

    val size: Int = byTitle.values.sumOf { it.size }

    /** Every file that is [song], itself included when [song] is a file in the index. */
    fun matches(song: Song): List<TrackEntity> {
        // Files already identified as this song, whatever their own title says.
        val identified = byVideoId[song.videoId].orEmpty()
        val byMetadata = byTitle[PlaylistMatcher.normalize(song.title)].orEmpty().filter { track ->
            SongIdentity.same(song, track.asSong())
        }
        return (identified + byMetadata).distinctBy { it.id }
    }

    private fun TrackEntity.asSong() = Song(
        videoId = videoId ?: localUri.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText ?: durationMs?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) },
        albumName = album,
    )
}
