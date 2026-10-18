package dev.auden.library

import android.content.Context
import android.util.Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import dev.auden.library.db.AudenDb
import dev.auden.library.db.PlaylistEntity
import dev.auden.library.db.PlaylistEntryEntity
import dev.auden.library.db.TrackEntity
import dev.auden.library.db.TrackOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies data that predates [AudenDb] into it, once.
 *
 * Deliberately a copy: the old stores are left untouched and still drive the UI
 * until the screens move over (Phase 2), so a bad migration costs nothing.
 * Currently covers the local playlists; download metadata and listening stats
 * follow with the phases that rewrite them.
 */
object LegacyMigration {
    private const val TAG = "AudenMigration"
    private const val PREFS = "auden_migration"
    private const val KEY_PLAYLISTS = "playlists_v1"

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PLAYLISTS, false)) return@withContext
        runCatching {
            val db = AudenDb.get(context)
            LocalPlaylistStore.readLegacy(context).forEach { playlist ->
                db.playlists().upsert(PlaylistEntity(id = playlist.id, title = playlist.title))
                val entries = playlist.songs.mapIndexed { index, song ->
                    PlaylistEntryEntity(
                        playlistId = playlist.id,
                        trackId = db.tracks().upsertByVideoId(song.toTrack()),
                        position = index,
                    )
                }
                db.playlists().insertEntries(entries)
            }
            prefs.edit().putBoolean(KEY_PLAYLISTS, true).apply()
            LocalPlaylists.reload(db)
        }.onFailure { Log.w(TAG, "playlist migration failed; will retry next launch", it) }
    }
}

internal fun Song.toTrack() = TrackEntity(
    videoId = videoId.takeIf { it.isNotBlank() },
    localUri = localUri,
    path = localPath,
    title = title,
    artist = artist,
    artistId = artistId,
    album = albumName,
    albumId = albumId,
    thumbnailUrl = thumbnailUrl,
    durationText = durationText,
    isVideo = isVideo,
    origin = if (localUri != null) TrackOrigin.LOCAL else TrackOrigin.YTM,
)
