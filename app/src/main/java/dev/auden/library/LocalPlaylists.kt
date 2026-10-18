package dev.auden.library

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import dev.auden.library.db.AudenDb
import dev.auden.library.db.PlaylistEntity
import dev.auden.library.db.PlaylistEntryEntity
import dev.auden.library.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Playlists that live on this device, in [AudenDb].
 *
 * The in-memory [playlists] flow is the source of truth for the UI and is
 * updated first; the database write follows, serialised so edits land in the
 * order they were made. The old preferences-backed store is read once, into
 * the database, by [LegacyMigration].
 */
object LocalPlaylists {
    /** Same prefix the Spotify import always used, so existing page routing keeps working. */
    const val ID_PREFIX = "sp_local_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    @Volatile private var db: AudenDb? = null

    /** Loads the playlists; safe to call again. */
    fun init(context: Context) {
        val database = AudenDb.get(context)
        db = database
        scope.launch { reload(database) }
    }

    suspend fun reload(database: AudenDb? = db) {
        if (database == null) return
        val rows = database.playlistWrites().all()
        _playlists.value = rows.map { LocalPlaylist(it.id, it.title, database.playlists().tracksOf(it.id).map(TrackEntity::toSong)) }
    }

    fun get(id: String): LocalPlaylist? {
        val clean = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == clean }
    }

    fun create(title: String, songs: List<Song> = emptyList()): LocalPlaylist {
        val playlist = LocalPlaylist(ID_PREFIX + System.currentTimeMillis(), title, songs.distinctBy { it.videoId })
        _playlists.value = listOf(playlist) + _playlists.value
        persist(playlist, insert = true)
        return playlist
    }

    /** Adds [songs] that are not already there; returns how many went in. */
    fun add(id: String, songs: List<Song>): Int {
        val playlist = get(id) ?: return 0
        val have = playlist.songs.mapTo(HashSet()) { it.videoId }
        val fresh = songs.filter { have.add(it.videoId) }
        if (fresh.isEmpty()) return 0
        replace(playlist.copy(songs = playlist.songs + fresh))
        return fresh.size
    }

    fun remove(id: String, videoId: String) {
        val playlist = get(id) ?: return
        replace(playlist.copy(songs = playlist.songs.filterNot { it.videoId == videoId }))
    }

    fun move(id: String, from: Int, to: Int) {
        val playlist = get(id) ?: return
        if (from !in playlist.songs.indices || to !in playlist.songs.indices || from == to) return
        val songs = playlist.songs.toMutableList()
        songs.add(to, songs.removeAt(from))
        replace(playlist.copy(songs = songs))
    }

    fun rename(id: String, title: String) {
        val playlist = get(id) ?: return
        _playlists.value = _playlists.value.map { if (it.id == playlist.id) it.copy(title = title) else it }
        val database = db ?: return
        scope.launch { writeLock.withLock { database.playlistWrites().rename(playlist.id, title, System.currentTimeMillis()) } }
    }

    /** Puts the playlist in exactly [songs]' order (the reorder page's result). */
    fun setOrder(id: String, songs: List<Song>) {
        val playlist = get(id) ?: return
        replace(playlist.copy(songs = songs))
    }

    /** The playlist as the account-playlist menus see it, so they can manage it too. */
    fun asUserPlaylist(id: String): com.music.bitchord.data.model.UserPlaylist? {
        val playlist = get(id) ?: return null
        return com.music.bitchord.data.model.UserPlaylist(
            playlistId = playlist.id,
            title = playlist.title,
            subtitle = "",
            thumbnailUrl = playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
            pageId = playlist.browseId,
        )
    }

    fun duplicate(id: String, title: String): LocalPlaylist? {
        val playlist = get(id) ?: return null
        return create(title, playlist.songs)
    }

    fun delete(id: String) {
        val playlist = get(id) ?: return
        _playlists.value = _playlists.value.filterNot { it.id == playlist.id }
        val database = db ?: return
        scope.launch { writeLock.withLock { database.playlists().delete(playlist.id) } }
    }

    private fun replace(updated: LocalPlaylist) {
        _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
        persist(updated, insert = false)
    }

    private fun persist(playlist: LocalPlaylist, insert: Boolean) {
        val database = db ?: return
        scope.launch {
            writeLock.withLock {
                val now = System.currentTimeMillis()
                if (insert) database.playlists().upsert(PlaylistEntity(playlist.id, playlist.title, now, now))
                else database.playlists().byId(playlist.id)?.let { database.playlists().upsert(it.copy(updatedAt = now)) }
                database.playlistWrites().clearEntries(playlist.id)
                database.playlists().insertEntries(
                    playlist.songs.mapIndexed { index, song ->
                        PlaylistEntryEntity(
                            playlistId = playlist.id,
                            trackId = database.tracks().upsertByVideoId(song.toTrack()),
                            position = index,
                        )
                    },
                )
            }
        }
    }
}

internal fun TrackEntity.toSong() = Song(
    videoId = videoId ?: localUri.orEmpty(),
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationText = durationText,
    artistId = artistId,
    albumId = albumId,
    albumName = album,
    isVideo = isVideo,
    localUri = localUri,
    localPath = path,
    localGenre = genre,
    trackNumber = trackNumber,
)
