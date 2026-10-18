package dev.auden.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class LocalRef(
    val id: Long,
    val localUri: String,
    val modifiedAt: Long?,
    val sizeBytes: Long?,
    val videoId: String?,
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: TrackEntity): Long

    @Query("SELECT * FROM track WHERE videoId = :videoId LIMIT 1")
    suspend fun byVideoId(videoId: String): TrackEntity?

    @Query("SELECT * FROM track WHERE localUri = :uri LIMIT 1")
    suspend fun byLocalUri(uri: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM track")
    suspend fun count(): Int

    /** What the indexer needs to tell new, changed and vanished files apart. */
    @Query("SELECT id, localUri, modifiedAt, sizeBytes, videoId FROM track WHERE localUri IS NOT NULL")
    suspend fun localRefs(): List<LocalRef>

    @androidx.room.Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM track WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** A file that is gone but whose song can still be streamed stays, as a streamable row. */
    @Query("UPDATE track SET localUri = NULL, folder = NULL, sizeBytes = NULL, modifiedAt = NULL WHERE id IN (:ids)")
    suspend fun detachFiles(ids: List<Long>)

    @Query("SELECT * FROM track WHERE localUri IS NOT NULL")
    fun observeLocal(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    /**
     * The row for this song, created from [track] on first sighting. Found by
     * video id, then by file, so the same song never gets two rows.
     */
    @Transaction
    suspend fun upsertByVideoId(track: TrackEntity): Long {
        track.videoId?.let { byVideoId(it) }?.let { return it.id }
        track.localUri?.let { byLocalUri(it) }?.let { return it.id }
        val id = insert(track)
        return if (id > 0) id else error("track could not be stored: ${track.title}")
    }
}

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("SELECT * FROM playlist ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT COUNT(*) FROM playlist")
    suspend fun count(): Int

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun byId(id: String): PlaylistEntity?

    @Query(
        "SELECT track.* FROM playlist_entry JOIN track ON track.id = playlist_entry.trackId " +
            "WHERE playlist_entry.playlistId = :playlistId ORDER BY playlist_entry.position"
    )
    suspend fun tracksOf(playlistId: String): List<TrackEntity>

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaylistWriteDao {
    @Query("SELECT * FROM playlist ORDER BY createdAt DESC")
    suspend fun all(): List<PlaylistEntity>

    @Query("DELETE FROM playlist_entry WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: String)

    @Query("UPDATE playlist SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)
}
