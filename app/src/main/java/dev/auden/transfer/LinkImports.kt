package dev.auden.transfer

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import dev.auden.library.ImportedOrigin
import dev.auden.library.LocalPlaylists

/** A playlist brought in from a pasted link, kept on this device. */
object LinkImports {
    /**
     * Keeps [songs] as a library playlist. With a [sourceId] (a pasted Spotify link) it is a copy of that list,
     * saved the way [dev.auden.library.CloudSaver] saves one: the same link again tops up the copy rather than
     * making another, and the copy records where it came from. The link names no track ids, so there is no
     * snapshot: a later refresh only adds.
     */
    fun keep(title: String, songs: List<Song>, sourceId: String?): LocalPlaylist {
        if (sourceId == null) return LocalPlaylists.create(title, songs)
        val origin = ImportedOrigin(snapshot = null, syncedAt = System.currentTimeMillis(), originTitle = title)
        return LocalPlaylists.saveFromSource(sourceId, title, songs, origin = origin).playlist
    }
}
