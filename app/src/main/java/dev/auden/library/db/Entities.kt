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
    /** Null until identification has looked at this file; see [IdentStatus]. */
    val identStatus: String? = null,
    /** How [videoId] was found: the file name, a search, or the listener. */
    val identSource: String? = null,
    /** A probable match awaiting the listener's yes or no. */
    val suggestedVideoId: String? = null,
    val suggestedTitle: String? = null,
    val suggestedArtist: String? = null,
    val identifiedAt: Long? = null,
)

/** Where identification left a local file. */
object IdentStatus {
    const val MATCHED = "MATCHED"
    /** A candidate was found but is not certain enough to apply unasked. */
    const val REVIEW = "REVIEW"
    const val NOT_FOUND = "NOT_FOUND"
    /** The listener said the suggestion was wrong; it is not offered again. */
    const val REJECTED = "REJECTED"
}

object IdentSource {
    const val FILE_NAME = "FILE_NAME"
    const val SEARCH = "SEARCH"
    const val MANUAL = "MANUAL"
}

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

/**
 * A lyrics response kept for a song; several providers may coexist, one per provider.
 *
 * Keyed by [songKey] (the song's video id, or its file uri for a file with no id) rather than by a row of
 * `track`, so a song that was never added to the library — streamed, in a playlist — can still keep
 * the lyrics the listener chose to download.
 */
@Entity(tableName = "lyrics_variant", primaryKeys = ["songKey", "provider"])
data class LyricsVariantEntity(
    val songKey: String,
    /** A [com.music.bitchord.data.lyrics.LyricsSource] name. */
    val provider: String,
    /** A [com.music.bitchord.data.lyrics.LyricsArtifactFormat] name. */
    val format: String,
    val content: String,
    val fetchedAt: Long = System.currentTimeMillis(),
)

/**
 * What the listener decided about one song's lyrics: which stored variant is the one to show, and how
 * far its timing is off. Either may be unset.
 */
@Entity(tableName = "song_lyrics_pref")
data class SongLyricsPrefEntity(
    @PrimaryKey val songKey: String,
    val activeProvider: String? = null,
    /** Replaces the global lyrics offset for this song when set. */
    val offsetMs: Int? = null,
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

/**
 * The YouTube Music song a Spotify track was matched to, kept so the same track is never searched for twice
 * and so a correction made by hand is never undone by a later automatic search.
 */
@Entity(tableName = "spotify_match")
data class SpotifyMatchEntity(
    @PrimaryKey val spotifyId: String,
    /** [SpotifyMatchStatus]. */
    val status: String,
    /** The matched song as JSON; null for a miss. */
    val songJson: String? = null,
    val matchedAt: Long = System.currentTimeMillis(),
)

object SpotifyMatchStatus {
    /** Found by the automatic search. */
    const val AUTO = "AUTO"
    /** Chosen by the listener; never replaced automatically. */
    const val MANUAL = "MANUAL"
    /** The search found nothing; tried again after a few days. */
    const val MISSING = "MISSING"
}
