package dev.auden.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    /** The row for [videoId], created from [track] when this is the first sighting. */
    @Transaction
    suspend fun upsertByVideoId(track: TrackEntity): Long {
        val id = track.videoId ?: return insert(track)
        return byVideoId(id)?.id ?: insert(track)
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
