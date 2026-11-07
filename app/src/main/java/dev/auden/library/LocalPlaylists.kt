package dev.auden.library

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylist
import androidx.room.withTransaction
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

    /**
     * Told, with the playlist's id, whenever songs are added to one that already existed: by hand, or
     * by a sync of its Spotify or YouTube Music original. The download side listens, so a playlist
     * downloaded whole takes its new songs without being asked.
     */
    @Volatile var onSongsAdded: ((String) -> Unit)? = null

    /** Loads the playlists; safe to call again. */
    fun init(context: Context) {
        val database = AudenDb.get(context)
        db = database
        scope.launch { reload(database) }
    }

    suspend fun reload(database: AudenDb? = db) {
        if (database == null) return
        val rows = database.playlistWrites().all()
        _playlists.value = rows.map { LocalPlaylist(it.id, it.title, database.playlists().tracksOf(it.id).map(TrackEntity::toSong), it.sourceId, it.coverUrl) }
    }

    fun get(id: String): LocalPlaylist? {
        val clean = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == clean }
    }

    fun create(title: String, songs: List<Song> = emptyList(), sourceId: String? = null, coverUrl: String? = null): LocalPlaylist {
        val playlist = LocalPlaylist(ID_PREFIX + System.currentTimeMillis(), title, songs.distinctBy { it.videoId }, sourceId, coverUrl)
        _playlists.value = listOf(playlist) + _playlists.value
        persist(playlist, insert = true)
        return playlist
    }

    /** The device copy of the cloud page [sourceId], if one was saved. */
    fun bySource(sourceId: String): LocalPlaylist? = _playlists.value.firstOrNull { it.sourceId == sourceId }

    /**
     * Saves a cloud playlist or album to the device library.
     *
     * Saving the same page twice does not make a second copy: the first one is
     * brought up to date with whatever the page has gained, so this doubles as
     * "sync". The listener's own additions stay.
     *
     * [sourceSpotifyIds], given for a Spotify page, is every track the page holds (matched or not). A copy
     * then also loses the songs that came from Spotify and are no longer there: those with a Spotify id
     * outside this set. Songs without one were added by the listener and are never taken out. Without it
     * (YouTube Music pages, which mark nothing) a sync only adds, and songs the listener removed come back.
     *
     * [coverUrl] is the page's own cover; a copy takes the newest one it is given.
     */
    fun saveFromSource(
        sourceId: String,
        title: String,
        songs: List<Song>,
        coverUrl: String? = null,
        sourceSpotifyIds: Set<String>? = null,
    ): SaveResult {
        val existing = bySource(sourceId)
        if (existing == null) return SaveResult(create(title, songs, sourceId, coverUrl), added = songs.distinctBy { it.videoId }.size, created = true)
        val kept = if (sourceSpotifyIds == null) existing.songs
        else existing.songs.filter { it.spotifyId == null || it.spotifyId in sourceSpotifyIds }
        val removed = existing.songs.size - kept.size
        val have = kept.mapTo(HashSet()) { it.videoId }
        val fresh = songs.filter { have.add(it.videoId) }
        val newCover = coverUrl?.takeIf { it.isNotBlank() } ?: existing.coverUrl
        if (removed > 0 || fresh.isNotEmpty() || newCover != existing.coverUrl) {
            replace(existing.copy(songs = kept + fresh, coverUrl = newCover))
        }
        return SaveResult(get(existing.id) ?: existing, added = fresh.size, created = false, removed = removed)
    }

    data class SaveResult(val playlist: LocalPlaylist, val added: Int, val created: Boolean, val removed: Int = 0)

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

    /** Takes out every entry that is [song], by id or by metadata; returns how many went. */
    fun removeMatching(id: String, song: Song): Int {
        val playlist = get(id) ?: return 0
        val kept = playlist.songs.filterNot { SongIdentity.same(it, song) }
        val removed = playlist.songs.size - kept.size
        if (removed > 0) replace(playlist.copy(songs = kept))
        return removed
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

    /** A Spotify track was matched to a different song: every library playlist that held the old match takes the new one. */
    fun replaceSpotifyMatch(spotifyId: String, replacement: Song) {
        _playlists.value.filter { p -> p.songs.any { it.spotifyId == spotifyId } }.forEach { p ->
            replace(p.copy(songs = p.songs.map { if (it.spotifyId == spotifyId) replacement.copy(spotifyId = spotifyId) else it }))
        }
    }

    /** The playlist as the account-playlist menus see it, so they can manage it too. */
    fun asUserPlaylist(id: String): com.music.bitchord.data.model.UserPlaylist? {
        val playlist = get(id) ?: return null
        return com.music.bitchord.data.model.UserPlaylist(
            playlistId = playlist.id,
            title = playlist.title,
            subtitle = "",
            thumbnailUrl = playlist.coverUrl ?: playlist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
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
        val before = _playlists.value.firstOrNull { it.id == updated.id }
        _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
        persist(updated, insert = false)
        if (before != null) {
            val had = before.songs.mapTo(HashSet()) { it.videoId }
            if (updated.songs.any { it.videoId !in had }) onSongsAdded?.invoke(updated.id)
        }
    }

    private fun persist(playlist: LocalPlaylist, insert: Boolean) {
        val database = db ?: return
        scope.launch {
            writeLock.withLock {
                val now = System.currentTimeMillis()
                if (insert) database.playlists().upsert(PlaylistEntity(playlist.id, playlist.title, now, now, sourceId = playlist.sourceId, coverUrl = playlist.coverUrl))
                else database.playlists().byId(playlist.id)?.let { database.playlists().upsert(it.copy(updatedAt = now, coverUrl = playlist.coverUrl)) }
                database.withTransaction {
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
    spotifyId = spotifyId,
)
