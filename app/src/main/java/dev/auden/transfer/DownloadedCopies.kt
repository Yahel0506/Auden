package dev.auden.transfer

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.PlaylistSync
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.download.SavedSongMetadata
import dev.auden.library.ImportedOrigin
import dev.auden.library.PlaylistOrigin
import dev.auden.library.Service

/**
 * YouTube Music playlists downloaded whole used to be re-read from YouTube Music by [PlaylistSync] to download
 * what they gained. Each gets a library copy that records its origin, so the playlist lives on in the library and
 * is refreshed from there when the listener asks (`PlaylistRefresher`).
 *
 * The download records are not rewritten: they keep their YouTube Music id and the downloads screens keep finding
 * them. [PlaylistSync] follows each through its copy instead ([copySourceId]): what the copy gains is downloaded.
 *
 * A downloaded Liked Music is not copied (likes are never a playlist); [likesRecords] lists it instead.
 */
object DownloadedCopies {

    /** A record downloaded from a YouTube Music playlist, and the source id its library copy is saved under. */
    data class Remote(val record: SavedCollection, val sourceId: String)

    /** The records downloaded from YouTube Music playlists, but for Liked Music. */
    fun remote(records: Collection<SavedCollection>): List<Remote> = records.mapNotNull { record ->
        val sourceId = copySourceId(record) ?: return@mapNotNull null
        Remote(record, sourceId)
    }.distinctBy { it.sourceId }

    /** The source id the library copy of a downloaded YouTube Music playlist is saved under; null for any other record. */
    fun copySourceId(record: SavedCollection): String? = origin(record)?.takeIf { !it.isLikesList }?.sourceId

    /** Downloads of the account's Liked Music: to be offered as likes, never deleted unasked. */
    fun likesRecords(records: Collection<SavedCollection>): List<SavedCollection> =
        records.filter { origin(it)?.isLikesList == true }

    private fun origin(record: SavedCollection): PlaylistOrigin? {
        val browseId = PlaylistSync.syncableBrowseId(record) ?: return null
        return PlaylistOrigin.of(browseId)?.takeIf { it.service == Service.YT_MUSIC }
    }

    /** The record's songs as its downloads describe them, in its order: for a copy made without reading the source. */
    fun songsOnDevice(record: SavedCollection, metadata: Map<String, SavedSongMetadata>): List<Song> =
        record.videoIds.distinct().mapNotNull { id ->
            val meta = metadata[id] ?: return@mapNotNull null
            Song(
                videoId = id,
                title = meta.title,
                artist = meta.artist,
                thumbnailUrl = meta.thumbnailUrl?.takeIf { it.startsWith("http") },
                durationText = meta.durationText,
                albumName = meta.albumName,
            )
        }

    data class Result(val copied: Int, val alreadyThere: Int, val fromDevice: Int, val pending: Int)

    /**
     * Makes the missing copies. Each is read from YouTube Music (a full listing, with its snapshot); one that has
     * failed [maxAttempts] times — a playlist deleted online, say — is made from what is downloaded of it instead,
     * with no snapshot. What is still neither is [Result.pending], for the next run.
     */
    suspend fun convert(
        engine: ImportEngine,
        source: MusicSource,
        records: Collection<SavedCollection>,
        metadata: Map<String, SavedSongMetadata>,
        library: ImportLibrary,
        attempts: Attempts,
        maxAttempts: Int = 3,
    ): Result {
        var copied = 0
        var already = 0
        var fromDevice = 0
        var pending = 0
        for (remote in remote(records)) {
            if (library.copyOf(remote.sourceId) != null) {
                already++
                continue
            }
            val record = remote.record
            val collection = RemoteCollection(
                sourceId = remote.sourceId,
                service = Service.YT_MUSIC,
                kind = PlaylistOrigin.of(remote.sourceId)!!.kind,
                title = record.title,
                subtitle = record.subtitle,
                thumbnailUrl = record.thumbnailUrl?.takeIf { it.startsWith("http") },
            )
            val report = engine.importCollection(source, collection)
            when {
                report.created.isNotEmpty() -> copied++
                report.complete -> already++
                attempts.failed(remote.sourceId) >= maxAttempts -> {
                    val songs = songsOnDevice(record, metadata)
                    if (songs.isEmpty()) {
                        pending++
                    } else {
                        library.createCopy(
                            remote.sourceId, record.title, songs, collection.thumbnailUrl,
                            ImportedOrigin(snapshot = null, syncedAt = null, originTitle = record.title),
                        )
                        fromDevice++
                    }
                }
                else -> pending++
            }
        }
        return Result(copied, already, fromDevice, pending)
    }

    /** How many times each source has failed to be read; counts the failure it is asked about. */
    fun interface Attempts {
        fun failed(sourceId: String): Int
    }
}
