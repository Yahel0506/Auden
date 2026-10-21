package dev.auden.home

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.stats.ListeningStats
import dev.auden.library.LocalPlaylists
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Reads the device — listening history, library, playlists — and builds Home's local shelves. */
object HomeLocal {

    /** Strings from the app's resources, so the shelves are named in the listener's language. */
    private class AndroidText(private val context: Context) : LocalShelfText {
        override fun title(kind: LocalShelfKind, artist: String?): String = when (kind) {
            LocalShelfKind.LOOP -> context.getString(R.string.home_shelf_loop)
            LocalShelfKind.REDISCOVER -> context.getString(R.string.home_shelf_rediscover)
            LocalShelfKind.RECENTLY_ADDED -> context.getString(R.string.home_shelf_recently_added)
            LocalShelfKind.PLAYLISTS -> context.getString(R.string.home_shelf_playlists)
            LocalShelfKind.ARTIST_MIX -> context.getString(R.string.home_shelf_artist_mix, artist.orEmpty())
            LocalShelfKind.LIBRARY_SHUFFLE -> context.getString(R.string.home_shelf_library)
        }

        override fun subtitle(kind: LocalShelfKind): String = when (kind) {
            LocalShelfKind.LOOP -> context.getString(R.string.home_shelf_loop_subtitle)
            LocalShelfKind.REDISCOVER -> context.getString(R.string.home_shelf_rediscover_subtitle)
            LocalShelfKind.RECENTLY_ADDED -> context.getString(R.string.home_shelf_recently_added_subtitle)
            LocalShelfKind.PLAYLISTS -> context.getString(R.string.home_shelf_playlists_subtitle)
            LocalShelfKind.ARTIST_MIX -> context.getString(R.string.home_shelf_artist_mix_subtitle)
            LocalShelfKind.LIBRARY_SHUFFLE -> context.getString(R.string.home_shelf_library_subtitle)
        }

        override fun songCount(count: Int): String =
            context.resources.getQuantityString(R.plurals.song_count_plural, count, count)
    }

    suspend fun load(context: Context): List<LocalShelf> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val cutoff = LocalDate.now().minusDays(30).toString()
        val played = ListeningStats.heardTracks().map { entry ->
            PlayedTrack(
                id = entry.id,
                title = entry.title,
                artist = entry.artist,
                art = entry.art,
                playsTotal = entry.plays,
                // "YYYY-MM-DD" keys sort as dates, so a string comparison is the date comparison.
                plays30 = entry.days.filterKeys { it >= cutoff }.values.sumOf { it.plays },
                lastPlayedAt = entry.last,
                localUri = entry.id.takeIf { it.startsWith("content://") || it.startsWith("file://") },
            )
        }
        val db = AudenDb.get(app)
        val library = db.tracks().all().map {
            LibraryTrack(
                id = it.videoId ?: it.localUri.orEmpty(),
                title = it.title,
                artist = it.artist,
                art = it.thumbnailUrl,
                localUri = it.localUri,
                addedAt = it.addedAt,
            )
        }.filter { it.id.isNotBlank() }
        val pinned = db.playlistWrites().all().filter { it.pinned }.map { it.id }.toSet()
        val playlists = LocalPlaylists.playlists.value.map {
            PlaylistSummary(
                id = it.id,
                title = it.title,
                songCount = it.songs.size,
                art = it.songs.firstOrNull { song -> song.thumbnailUrl != null }?.thumbnailUrl,
                pinned = it.id in pinned,
            )
        }
        LocalShelves.build(played, library, playlists, now, AndroidText(app))
    }
}
