package dev.auden.dj

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The songs the DJ can choose from: the library, and everything ever heard. */
object DjPool {
    /** Enough variety for any theme, and cheap enough to group on a phone in a few milliseconds. */
    const val CAP = 3_000

    /** Plays that make a file kept only on the phone part of the listener's rotation, and so the DJ's to play. */
    const val LOCAL_MIN_PLAYS = 5

    class Loaded(val tracks: List<DjTrack>, val songs: Map<String, Song>)

    suspend fun load(context: Context): Loaded = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val cutoff = LocalDate.now().minusDays(30).toString()
        val heard = ListeningStats.heardTracks().associateBy { it.id }
        val songs = HashMap<String, Song>()
        val byId = LinkedHashMap<String, DjTrack>()

        AudenDb.get(app).tracks().all().forEach { row ->
            val id = row.videoId ?: row.localUri ?: return@forEach
            val entry = heard[id]
            // A file that is only on the phone is the listener's own business, not the DJ's: it plays only
            // when it is something they keep coming back to.
            if (row.videoId == null && (entry?.plays ?: 0) < LOCAL_MIN_PLAYS) return@forEach
            byId[id] = DjTrack(
                id = id, title = row.title, artist = row.artist, album = row.album, genre = row.genre,
                year = row.year, trackNumber = row.trackNumber, art = row.thumbnailUrl, localUri = row.localUri,
                plays = entry?.plays ?: 0,
                plays30 = entry?.days?.filterKeys { it >= cutoff }?.values?.sumOf { it.plays } ?: 0,
                lastPlayedAt = entry?.last ?: 0L,
                addedAt = row.addedAt,
            )
            songs[id] = Song(
                videoId = id, title = row.title, artist = row.artist, thumbnailUrl = row.thumbnailUrl,
                durationText = row.durationText, artistId = row.artistId, albumId = row.albumId,
                albumName = row.album, localUri = row.localUri, localPath = row.path,
                localGenre = row.genre, trackNumber = row.trackNumber, spotifyId = row.spotifyId,
            )
        }
        // Heard but never added: a streamed favourite. It has no tags, so it can serve an artist,
        // favourites or rediscover block, and nothing that needs a year or a genre.
        heard.values.forEach { entry ->
            if (entry.id in byId || entry.title.isBlank() || entry.artist.isBlank()) return@forEach
            val isFile = entry.id.startsWith("content://") || entry.id.startsWith("file://")
            if (isFile && entry.plays < LOCAL_MIN_PLAYS) return@forEach
            byId[entry.id] = DjTrack(
                id = entry.id, title = entry.title, artist = entry.artist, album = entry.album, art = entry.art,
                localUri = entry.id.takeIf { it.startsWith("content://") || it.startsWith("file://") },
                plays = entry.plays,
                plays30 = entry.days.filterKeys { it >= cutoff }.values.sumOf { it.plays },
                lastPlayedAt = entry.last,
            )
            songs[entry.id] = Song(
                videoId = entry.id, title = entry.title, artist = entry.artist, thumbnailUrl = entry.art,
                albumName = entry.album, artistId = entry.artistId,
                localUri = entry.id.takeIf { it.startsWith("content://") || it.startsWith("file://") },
            )
        }
        // The listener's playlists: curated on purpose, so their songs are the DJ's to play even when
        // they are files kept on the phone or have hardly been played. Each song knows which it is in.
        dev.auden.library.LocalPlaylists.playlists.value.forEach { playlist ->
            playlist.songs.forEach { song ->
                val id = song.videoId.takeIf { it.isNotBlank() } ?: return@forEach
                val existing = byId[id]
                if (existing != null) {
                    byId[id] = existing.copy(playlists = existing.playlists + playlist.title)
                } else {
                    val entry = heard[id]
                    byId[id] = DjTrack(
                        id = id, title = song.title, artist = song.artist, album = song.albumName,
                        art = song.thumbnailUrl, localUri = song.localUri,
                        plays = entry?.plays ?: 0,
                        plays30 = entry?.days?.filterKeys { it >= cutoff }?.values?.sumOf { it.plays } ?: 0,
                        lastPlayedAt = entry?.last ?: 0L,
                        playlists = listOf(playlist.title),
                    )
                    songs.putIfAbsent(id, song)
                }
            }
        }
        val capped = byId.values.sortedByDescending { it.plays * 2 + it.plays30 * 3 + (if (it.localUri != null) 1 else 0) }.take(CAP)
        Loaded(capped, songs)
    }
}
