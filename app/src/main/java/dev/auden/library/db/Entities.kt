package dev.auden.library.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a [TrackEntity] first came from. */
object TrackOrigin {
    const val YTM = "YTM"
    const val LOCAL = "LOCAL"
    const val DOWNLOAD = "DOWNLOAD"
    const val SPOTIFY = "SPOTIFY"
}

/**
 * One song in the Auden library, whatever way it can be played.
 *
 * A row may have a [videoId] (streamable), a [localUri] (a file on the device),
 * or both once the two have been matched. Album and artist stay as plain
 * strings for now; the Phase 2 indexer normalises them into their own tables.
 */
@Entity(
    tableName = "track",
    indices = [
        Index("videoId"),
        Index("spotifyId"),
        Index("isrc"),
        Index("localUri", unique = true),
        Index("path"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String? = null,
    val spotifyId: String? = null,
    val isrc: String? = null,
    val localUri: String? = null,
    /** Absolute path of the file, for matching imported playlists. */
    val path: String? = null,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    /** The row's own "3:45" text, kept as YouTube Music gave it. */
    val durationText: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    /** Folder the file sits in (relative path, for the Folders view). */
    val folder: String? = null,
    val sizeBytes: Long? = null,
    /** File's last-modified time in ms, the indexer's change check. */
    val modifiedAt: Long? = null,
    val isVideo: Boolean = false,
    val origin: String = TrackOrigin.YTM,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    /** Same id the old `LocalPlaylistStore` used, so existing pages keep resolving. */
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
    /** The cloud page this was saved from (a browse id), so saving it again syncs instead of duplicating. */
    val sourceId: String? = null,
)

/**
 * A track's place in a playlist. [entryId] is its own identity, so the same
 * song twice is two entries; [position] is dense and zero-based.
 */
@Entity(
    tableName = "playlist_entry",
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId", "position"), Index("trackId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: String,
    val trackId: Long,
    val position: Int,
)

/** A lyrics response kept for one track; several providers may coexist. */
@Entity(
    tableName = "lyrics_variant",
    foreignKeys = [ForeignKey(TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId", "provider", unique = true)],
)
data class LyricsVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val provider: String,
    val format: String,
    val content: String,
    val fetchedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false,
    /** Added on top of the global lyrics offset. */
    val offsetMs: Int = 0,
)

/** One listen, the table the stats screens will read from. */
@Entity(
    tableName = "play_event",
    foreignKeys = [ForeignKey(TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId"), Index("startedAt")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val startedAt: Long,
    val listenedMs: Long,
    val completed: Boolean,
)
