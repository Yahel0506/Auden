package dev.auden.home

import com.music.bitchord.data.model.ShelfItem
import java.util.Random

/** The shelves Home can build from what is on the device and what has been listened to. */
enum class LocalShelfKind {
    /** The last thirty days' most played. */
    LOOP,
    /** Loved once, not heard for a long while. */
    REDISCOVER,
    /** Files and downloads that arrived most recently. */
    RECENTLY_ADDED,
    /** Playlists kept on this device, pinned first. */
    PLAYLISTS,
    /** Songs of the artist heard most lately, from the library and from history. */
    ARTIST_MIX,
    /** A random pick of the files on the device: music that plays with no connection. */
    LIBRARY_SHUFFLE,
}

/** One song's listening history, merged across every month kept. */
data class PlayedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val art: String?,
    val playsTotal: Int,
    /** Plays in the thirty days up to now. */
    val plays30: Int,
    val lastPlayedAt: Long,
    /** The file, when the track is one — local tracks are identified by their uri. */
    val localUri: String?,
)

/** A song the library holds. */
data class LibraryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val art: String?,
    val localUri: String?,
    val addedAt: Long,
)

data class PlaylistSummary(
    val id: String,
    val title: String,
    val songCount: Int,
    val art: String?,
    val pinned: Boolean,
)

/** What a shelf is called and how a count is worded, in the listener's language. */
interface LocalShelfText {
    fun title(kind: LocalShelfKind, artist: String? = null): String
    fun subtitle(kind: LocalShelfKind): String
    fun songCount(count: Int): String
}

/** A finished shelf, before Home decides where it goes. */
data class LocalShelf(
    val kind: LocalShelfKind,
    val title: String,
    val subtitle: String,
    val items: List<ShelfItem>,
)

/**
 * Builds the local shelves from listening history and the library.
 *
 * Pure, and handed the date and a seed: the same day gives the same shelves, so Home does not
 * reshuffle every time it is opened, and the rules are testable without a device. A shelf that has
 * too little to show is not built at all, which is how a new install stays empty of them rather
 * than showing three songs under a big heading.
 */
object LocalShelves {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val SHELF_SIZE = 20
    const val MIN_ITEMS = 4

    /** Plays in the last month that make a song "on repeat" rather than just heard. */
    const val LOOP_MIN_PLAYS = 2

    /** How many plays make a song loved, and how long without hearing it makes it forgotten. */
    const val REDISCOVER_MIN_PLAYS = 5
    const val REDISCOVER_AFTER_DAYS = 90

    fun build(
        played: List<PlayedTrack>,
        library: List<LibraryTrack>,
        playlists: List<PlaylistSummary>,
        now: Long,
        text: LocalShelfText,
    ): List<LocalShelf> {
        val seed = now / DAY_MS
        return listOfNotNull(
            loop(played, text),
            rediscover(played, now, seed, text),
            recentlyAdded(library, text),
            playlists(playlists, text),
            artistMix(played, library, seed, text),
            libraryShuffle(library, seed, text),
        )
    }

    private fun loop(played: List<PlayedTrack>, text: LocalShelfText): LocalShelf? {
        val tracks = played
            .filter { it.plays30 >= LOOP_MIN_PLAYS }
            .sortedWith(compareByDescending<PlayedTrack> { it.plays30 }.thenByDescending { it.lastPlayedAt })
            .take(SHELF_SIZE)
        return shelf(LocalShelfKind.LOOP, tracks.map { it.item() }, text)
    }

    private fun rediscover(played: List<PlayedTrack>, now: Long, seed: Long, text: LocalShelfText): LocalShelf? {
        val cutoff = now - REDISCOVER_AFTER_DAYS * DAY_MS
        val tracks = played
            .filter { it.playsTotal >= REDISCOVER_MIN_PLAYS && it.lastPlayedAt in 1 until cutoff }
            .sortedByDescending { it.playsTotal }
            .take(SHELF_SIZE * 2)
            .shuffled(Random(seed + 1))
            .take(SHELF_SIZE)
        return shelf(LocalShelfKind.REDISCOVER, tracks.map { it.item() }, text)
    }

    private fun recentlyAdded(library: List<LibraryTrack>, text: LocalShelfText): LocalShelf? {
        val tracks = library
            .filter { it.localUri != null }
            .sortedByDescending { it.addedAt }
            .take(SHELF_SIZE)
        return shelf(LocalShelfKind.RECENTLY_ADDED, tracks.map { it.item() }, text)
    }

    private fun playlists(playlists: List<PlaylistSummary>, text: LocalShelfText): LocalShelf? {
        val items = playlists
            .filter { it.songCount > 0 }
            .sortedWith(compareByDescending<PlaylistSummary> { it.pinned })
            .take(SHELF_SIZE)
            .map {
                ShelfItem(
                    title = it.title,
                    subtitle = text.songCount(it.songCount),
                    thumbnailUrl = it.art,
                    videoId = null,
                    browseId = "local:playlist:${it.id}",
                )
            }
        // A single playlist is worth a shelf: unlike songs, there is nothing more to wait for.
        if (items.isEmpty()) return null
        return LocalShelf(LocalShelfKind.PLAYLISTS, text.title(LocalShelfKind.PLAYLISTS), text.subtitle(LocalShelfKind.PLAYLISTS), items)
    }

    private fun artistMix(played: List<PlayedTrack>, library: List<LibraryTrack>, seed: Long, text: LocalShelfText): LocalShelf? {
        val byArtist = played
            .filter { lead(it.artist).isNotEmpty() }
            .groupBy { lead(it.artist).lowercase() }
        // The artist heard most lately, then most overall; ties go to whoever was heard last.
        val top = byArtist.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<PlayedTrack>>> { e -> e.value.sumOf { it.plays30 } }
                .thenBy { e -> e.value.sumOf { it.playsTotal } }
                .thenBy { e -> e.value.maxOf { it.lastPlayedAt } },
        ) ?: return null
        val name = lead(top.value.first().artist)
        val fromHistory = top.value.map { it.item() }
        val fromLibrary = library
            .filter { lead(it.artist).equals(name, ignoreCase = true) }
            .map { it.item() }
        val items = (fromHistory + fromLibrary)
            .distinctBy { it.videoId ?: it.title }
            .shuffled(Random(seed + 2))
            .take(SHELF_SIZE)
        return shelf(LocalShelfKind.ARTIST_MIX, items, text, artist = name)
    }

    private fun libraryShuffle(library: List<LibraryTrack>, seed: Long, text: LocalShelfText): LocalShelf? {
        val items = library
            .filter { it.localUri != null }
            .shuffled(Random(seed + 3))
            .take(SHELF_SIZE)
            .map { it.item() }
        return shelf(LocalShelfKind.LIBRARY_SHUFFLE, items, text)
    }

    private fun shelf(kind: LocalShelfKind, items: List<ShelfItem>, text: LocalShelfText, artist: String? = null): LocalShelf? =
        if (items.size < MIN_ITEMS) null else LocalShelf(kind, text.title(kind, artist), text.subtitle(kind), items)

    /** "A & B", "A, B" and "A feat. B" are all A's. */
    internal fun lead(credit: String): String =
        credit.split(" & ", ", ", " feat. ", " ft. ", " x ", ignoreCase = true).first().trim()

    private fun PlayedTrack.item() = ShelfItem(
        title = title, subtitle = artist, thumbnailUrl = art, videoId = id, browseId = null,
        localUri = localUri ?: id.takeIf { it.isUri() },
    )

    private fun LibraryTrack.item() = ShelfItem(
        title = title, subtitle = artist, thumbnailUrl = art, videoId = id, browseId = null,
        localUri = localUri,
    )

    /** Local files are identified by their uri; nothing else here contains a scheme. */
    internal fun String.isUri(): Boolean = startsWith("content://") || startsWith("file://")
}
