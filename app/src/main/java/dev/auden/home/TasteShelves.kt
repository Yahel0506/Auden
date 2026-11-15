package dev.auden.home

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Home's recommendations for a listener with no account: what this app has heard them play, taken out to
 * YouTube Music's own catalogue — what plays on after their favourite songs, and who sounds like their favourite
 * artists. The same questions an account's history would answer, put from the app's own listening instead.
 */
object TasteShelves {
    private const val SONG_SEEDS = 2
    private const val ARTIST_SEEDS = 2
    private const val SEED_POOL = 6
    private const val SHELF_SIZE = 14

    suspend fun build(context: Context): List<HomeShelf> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // This month first: it is what they are listening to now. All time when the month is thin.
        var summary = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
        if (summary == null || summary.songs.size < 3) {
            summary = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull() ?: return@withContext emptyList()
        }
        val heard = summary.songs.map { it.song.videoId }.toSet()
        val songSeeds = summary.songs.take(SEED_POOL).map { it.song }.shuffled().take(SONG_SEEDS)
        val artistSeeds = summary.artists.filter { it.browseId != null }.take(SEED_POOL).shuffled().take(ARTIST_SEEDS)

        coroutineScope {
            val songShelves = songSeeds.map { seed ->
                async {
                    val queue = YtMusicRepository.radio(seed.videoId).getOrNull().orEmpty()
                        .filter { it.videoId != seed.videoId && it.videoId !in heard }
                        .distinctBy { it.videoId }
                        .take(SHELF_SIZE)
                    if (queue.size < 4) null
                    else HomeShelf(
                        title = app.getString(R.string.home_because_you_listened, seed.title),
                        subtitle = seed.artist,
                        items = queue.map(::songItem),
                    )
                }
            }
            val artistShelves = artistSeeds.map { artist ->
                async {
                    val page = YtMusicRepository.artistLanding(artist.browseId!!).getOrNull() ?: return@async emptyList<HomeShelf>()
                    val name = page.name ?: artist.title
                    // The page's own row of artists around this one.
                    val similar = page.sections
                        .firstOrNull { s -> s.items.size >= 3 && s.items.all { it.browseId?.startsWith("UC") == true } }
                    buildList {
                        similar?.let {
                            add(HomeShelf(title = app.getString(R.string.home_more_like_artist, name), items = it.items.take(SHELF_SIZE)))
                        }
                        val top = page.songs.filter { it.videoId !in heard }.take(SHELF_SIZE)
                        if (top.size >= 4) add(HomeShelf(title = app.getString(R.string.home_best_of_artist, name), items = top.map(::songItem)))
                    }
                }
            }
            // Interleaved, so the first thing under the feed's head is not three rows from one taste.
            val a = songShelves.mapNotNull { it.await() }
            val b = artistShelves.map { it.await() }
            buildList {
                val longest = maxOf(a.size, b.size)
                for (i in 0 until longest) {
                    a.getOrNull(i)?.let(::add)
                    b.getOrNull(i)?.let(::addAll)
                }
            }.distinctBy { it.title }
        }
    }

    private fun songItem(song: Song) = ShelfItem(
        title = song.title,
        subtitle = song.artist,
        thumbnailUrl = song.thumbnailUrl,
        videoId = song.videoId,
        browseId = null,
        isVideo = song.isVideo,
    )
}
