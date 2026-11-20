package com.music.bitchord.data

import java.util.concurrent.ConcurrentHashMap

/**
 * An artist's own picture, by the page that opens them, kept as artist pages are read. What is played from an artist
 * is remembered in Recents under the artist's page, and the song that happens to be playing is not their picture:
 * this is what the Recents card is given instead.
 */
object ArtistPhotos {
    private val photos = ConcurrentHashMap<String, String>()

    /** Whether [browseId] is an artist's page (a YouTube Music channel). */
    fun isArtist(browseId: String?): Boolean = browseId != null && (browseId.startsWith("UC") || browseId.startsWith("MPLA"))

    fun put(browseId: String, url: String?) {
        if (isArtist(browseId) && !url.isNullOrBlank()) photos[browseId] = url
    }

    fun get(browseId: String): String? = photos[browseId]
}
