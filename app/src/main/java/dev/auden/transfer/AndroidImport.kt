package dev.auden.transfer

import android.content.Context
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyTrack
import dev.auden.library.ImportedOrigin
import dev.auden.library.LocalPlaylists
import dev.auden.library.OriginKind
import dev.auden.library.Service
import dev.auden.library.db.AudenDb
import dev.auden.library.db.FollowedArtistEntity
import dev.auden.library.spotify.SpotifyMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** [ImportLibrary] over the app's own stores: [OfflinePlaylists] (likes), [LocalPlaylists] and [AudenDb]. */
class AndroidImportLibrary(context: Context) : ImportLibrary {
    private val db = AudenDb.get(context.applicationContext)

    override fun hiddenLikes(): Set<String> = OfflinePlaylists.hiddenAccountLikes.value

    override fun appendLikes(songs: List<Song>): Int = OfflinePlaylists.appendLikes(songs)

    override fun copyOf(sourceId: String): String? = LocalPlaylists.bySource(sourceId)?.id

    override fun createCopy(sourceId: String, title: String, songs: List<Song>, coverUrl: String?, origin: ImportedOrigin): String =
        LocalPlaylists.create(title, songs, sourceId, coverUrl, origin).id

    override suspend fun followedArtistIds(): Set<String> = withContext(Dispatchers.IO) {
        db.origins().artists().mapTo(HashSet()) { it.id }
    }

    override suspend fun follow(artists: List<FollowedArtistEntity>): Int = withContext(Dispatchers.IO) {
        db.origins().followAll(artists).count { it != -1L }
    }
}

/**
 * [ImportResolver] over the existing matchers: a Spotify track through [SpotifyMatches] (its remembered matches
 * first, so a song matched before is not searched for again, and a match corrected by hand is kept), an album or
 * an artist through a YouTube Music search.
 */
class AndroidImportResolver(context: Context) : ImportResolver {
    private val app = context.applicationContext

    override suspend fun song(track: SpotifyTrack): Song? = runCatching { SpotifyMatches.resolve(app, track) }.getOrNull()

    override suspend fun ytAlbum(album: RemoteCollection): RemoteCollection? {
        val query = listOf(album.title, album.subtitle).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.ALBUMS).getOrNull().orEmpty()
            .filterIsInstance<SearchResult.Browse>().map { it.item }
        val hit = AlbumMatch.pick(candidates, album.title, album.subtitle) ?: return null
        return RemoteCollection(hit.browseId, Service.YT_MUSIC, OriginKind.ALBUM, hit.title, hit.subtitle, hit.thumbnailUrl ?: album.thumbnailUrl)
    }

    override suspend fun ytArtistId(artist: RemoteArtist): String? =
        YtMusicRepository.findArtistPageId(artist.name).getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Which YouTube Music album search result is a given (Spotify) album: the same title once editions and
 * punctuation are set aside, by an artist the result names. Strict on purpose — a wrong album copied whole is
 * worse than one reported as not found.
 */
object AlbumMatch {
    fun pick(candidates: List<BrowseItem>, title: String, artist: String): BrowseItem? {
        val wanted = normalize(title)
        val bare = normalize(withoutEdition(title))
        if (wanted.isEmpty()) return null
        val artists = artist.split(',', '&').map(::normalize).filter { it.isNotEmpty() }
        val albums = candidates.filter { item ->
            item.type == BrowseType.ALBUM && item.browseId.startsWith("MPREb") &&
                normalize(item.subtitle).let { byline -> artists.isEmpty() || artists.any { byline.contains(it) } }
        }
        // The very title first; only then the same title under another edition — and a live album is never the
        // studio one (nor the other way round).
        val live = Regex("""\blive\b""")
        return albums.firstOrNull { normalize(it.title) == wanted }
            ?: albums.firstOrNull {
                normalize(withoutEdition(it.title)) == bare &&
                    live.containsMatchIn(normalize(it.title)) == live.containsMatchIn(wanted)
            }
    }

    /** "Abbey Road (Remastered 2019)", "Views - Deluxe" → the title without the edition. */
    internal fun withoutEdition(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
}
