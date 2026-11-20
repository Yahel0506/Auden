package dev.auden.library

import android.content.Context
import com.music.bitchord.data.model.ShelfItem
import dev.auden.library.db.AudenDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The artists (and podcast shows) followed in Auden — brought over with "Transfer your music" — as cards for the
 * Library's sections. Newest first. The card opens the artist's page when it has one on YouTube Music; one only
 * known by its Spotify id is listed by name and has nothing to open.
 */
object FollowedArtists {
    fun items(context: Context): Flow<List<ShelfItem>> =
        AudenDb.get(context.applicationContext).origins().observeArtists().map { rows ->
            rows.sortedByDescending { it.addedAt }.map { artist ->
                ShelfItem(
                    title = artist.name,
                    subtitle = "",
                    thumbnailUrl = artist.thumb,
                    videoId = null,
                    browseId = artist.id.takeUnless { it.startsWith("spotify:artist:") },
                    // A followed show (MPSP…) shares the table but is not an artist.
                    isArtist = !artist.id.startsWith("MPSP"),
                    platform = when (artist.originService) {
                        Service.SPOTIFY.name -> ShelfItem.PLATFORM_SPOTIFY
                        Service.YT_MUSIC.name -> ShelfItem.PLATFORM_YT_MUSIC
                        else -> null
                    },
                )
            }
        }
}
