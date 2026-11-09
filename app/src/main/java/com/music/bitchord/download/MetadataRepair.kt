package com.music.bitchord.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.toEnhancedLrc
import com.music.bitchord.data.lyrics.toLrc
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import dev.auden.library.lyrics.LyricsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Finds the tags a downloaded or device file is missing — title, artist, album
 * and cover — and writes them in from the song's own record, overwriting the
 * file with the same bytes plus what was missing.
 *
 * Lyrics are not part of the check: they have their own action. They are carried
 * across the rewrite, from the stored lyrics where there are some and from the
 * file's own tag where there are not, so repairing a file never costs it its
 * words.
 *
 * What is kept is never replaced. A field the file already has stays as it is;
 * only a missing one is filled. A song is only repaired when its recording is
 * certain: a download is looked up by its id, and a device file is matched with
 * the same [TrackMatcher] rule the library uses, with no guessing.
 */
object MetadataRepair {

    private const val TAG = "BitChord"

    /** Longest side of a cover that came out of a file, once it has to be re-encoded. */
    private const val JPEG_QUALITY = 92

    /** Which tags a file is missing. Lyrics are deliberately not one of them. */
    enum class Gap { TITLE, ARTIST, ALBUM, COVER }

    /** What a file's tags say, as far as the platform reads them. */
    internal data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val track: Int?,
        val year: Int?,
        /** The cover as the file carries it, or null when it has none. */
        val picture: ByteArray?,
    )

    enum class Outcome { COMPLETE, REPAIRED, UNAVAILABLE, NOT_A_FILE }

    data class Report(
        val repaired: Int = 0,
        val complete: Int = 0,
        val unavailable: Int = 0,
    ) {
        operator fun plus(outcome: Outcome): Report = when (outcome) {
            Outcome.REPAIRED -> copy(repaired = repaired + 1)
            Outcome.COMPLETE -> copy(complete = complete + 1)
            Outcome.UNAVAILABLE -> copy(unavailable = unavailable + 1)
            Outcome.NOT_A_FILE -> this
        }

        val checked: Int get() = repaired + complete + unavailable
    }

    /** The gaps in [tags], for a pure check. */
    internal fun gapsOf(tags: Tags): Set<Gap> = buildSet {
        if (tags.title.isNullOrBlank()) add(Gap.TITLE)
        if (tags.artist.isNullOrBlank()) add(Gap.ARTIST)
        if (tags.album.isNullOrBlank()) add(Gap.ALBUM)
        if (tags.picture == null || tags.picture.isEmpty()) add(Gap.COVER)
    }

    /**
     * Runs the repair for [songs] in the background, then says how it went in one
     * toast. Only the songs that are files are looked at; a song that streams is
     * skipped without a word.
     */
    fun start(context: Context, songs: List<Song>) {
        val app = context.applicationContext
        scope.launch {
            val report = repairAll(app, songs)
            withContext(Dispatchers.Main) {
                val text = if (report.checked == 0) {
                    app.getString(R.string.metadata_repair_nothing)
                } else {
                    app.getString(
                        R.string.metadata_repair_done,
                        report.repaired,
                        report.complete,
                        report.unavailable,
                    )
                }
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The repair of every song in [songs] that is a file, one after another. */
    suspend fun repairAll(context: Context, songs: List<Song>): Report {
        var report = Report()
        for (song in songs.distinctBy { it.videoId + "|" + it.localUri }) {
            report = report + repairOne(context, song)
        }
        return report
    }

    /** Repairs one song's file. Returns [Outcome.NOT_A_FILE] for one that only streams. */
    suspend fun repairOne(context: Context, song: Song): Outcome = withContext(Dispatchers.IO) {
        val downloaded = Downloads.savedMetadata.value.containsKey(song.videoId)
        val uri: Uri = (if (downloaded) Downloads.savedUri(context, song.videoId) else null)
            ?: song.localUri?.let(Uri::parse)
            ?: return@withContext Outcome.NOT_A_FILE
        val name = DownloadStore.relativeName(context, uri) ?: uri.lastPathSegment.orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!MediaTagger.carriesTags(extension)) return@withContext Outcome.NOT_A_FILE

        val before = readTags(context, uri) ?: return@withContext Outcome.UNAVAILABLE
        val gaps = gapsOf(before)
        if (gaps.isEmpty()) return@withContext Outcome.COMPLETE

        val track = recordingFor(song, before, downloaded)
        val fetchedCover = if (Gap.COVER in gaps) MediaTagger.artworkFor(context, track) else null
        val filled = buildSet {
            if (Gap.TITLE in gaps && track.title.isNotBlank()) add(Gap.TITLE)
            if (Gap.ARTIST in gaps && track.artist.isNotBlank()) add(Gap.ARTIST)
            if (Gap.ALBUM in gaps && !track.albumName.isNullOrBlank()) add(Gap.ALBUM)
            if (Gap.COVER in gaps && fetchedCover != null) add(Gap.COVER)
        }
        if (filled.isEmpty()) return@withContext Outcome.UNAVAILABLE

        // Written over the file, so everything it carries has to be handed back
        // in: the file's own values win, the new ones fill what was missing, and
        // the cover and lyrics are passed through rather than dropped.
        val merged = track.copy(
            title = before.title?.takeIf { it.isNotBlank() } ?: track.title,
            artist = before.artist?.takeIf { it.isNotBlank() } ?: track.artist,
            albumName = before.album?.takeIf { it.isNotBlank() } ?: track.albumName,
        )
        val artwork = fetchedCover ?: before.picture?.let(::asArtwork)
        MediaTagger.embed(
            context = context,
            uri = uri,
            track = merged,
            extension = extension,
            lyrics = lyricsFor(context, song, uri, downloaded),
            artwork = artwork,
            extras = extrasFor(song, before, downloaded),
        )

        // Checked rather than trusted: a rewrite that did not take is reported as
        // unavailable, not as repaired.
        val after = readTags(context, uri) ?: return@withContext Outcome.UNAVAILABLE
        if (gapsOf(after).size < gaps.size) {
            Log.d(TAG, "repaired ${song.videoId}: ${gaps - gapsOf(after)}")
            Outcome.REPAIRED
        } else {
            Outcome.UNAVAILABLE
        }
    }

    /** The tags [uri] carries, or null when the platform cannot read the file. */
    internal fun readTags(context: Context, uri: Uri): Tags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim(),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim(),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim(),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim(),
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.trim()?.toIntOrNull(),
                picture = retriever.embeddedPicture,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not read tags of $uri: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * The recording the file is of. A download is looked up by its id. A device
     * file is matched by its title and artist, and taken only when the match is
     * certain; otherwise the song's own values stand and nothing new is filled.
     */
    private suspend fun recordingFor(song: Song, tags: Tags, downloaded: Boolean): Song {
        if (downloaded) return runCatching { YtMusicRepository.resolveAudio(song) }.getOrDefault(song)
        val target = TrackMatcher.Target(
            title = tags.title?.takeIf { it.isNotBlank() } ?: song.title,
            artist = tags.artist?.takeIf { it.isNotBlank() } ?: song.artist,
            durationSec = TrackMatcher.secondsOf(song.durationText),
            album = tags.album?.takeIf { it.isNotBlank() } ?: song.albumName,
        )
        if (target.artist.isBlank()) return song
        val candidates = linkedMapOf<String, Song>()
        for (query in TrackMatcher.queries(target).take(2)) {
            runCatching { YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() }.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.forEach { candidates.putIfAbsent(it.song.videoId, it.song) }
            if (TrackMatcher.best(candidates.values.toList(), target) != null) break
        }
        return TrackMatcher.best(candidates.values.toList(), target) ?: song
    }

    /**
     * The lyrics to hand back into the rewrite. A download's stored lyrics are the
     * fullest copy, including the word-timed form; a file without any stored copy
     * keeps the lines its own tag holds.
     */
    private suspend fun lyricsFor(context: Context, song: Song, uri: Uri, downloaded: Boolean): LyricsTag.Embeddable? {
        if (downloaded) {
            LyricsStore.active(context, song.videoId)?.result?.artifact?.let { artifact ->
                return LyricsTag.Embeddable(
                    plain = artifact.lines.toLrc(),
                    enhanced = artifact.lines.toEnhancedLrc().takeIf { it.isNotBlank() },
                )
            }
        }
        val lines = EmbeddedLyrics.forUri(context, uri.toString()) ?: return null
        return LyricsTag.Embeddable(plain = lines.toLrc(), enhanced = null)
    }

    /** The positional and descriptive tags a rewrite would otherwise drop. */
    private fun extrasFor(song: Song, tags: Tags, downloaded: Boolean): TagExtras = TagExtras(
        albumArtist = tags.albumArtist?.takeIf { it.isNotBlank() },
        track = tags.track,
        year = tags.year,
        comment = "https://music.youtube.com/watch?v=${song.videoId}"
            .takeIf { downloaded && !song.videoId.startsWith("content:") },
    )

    /** A file's own cover, handed back as the JPEG the taggers expect. */
    private fun asArtwork(picture: ByteArray): MediaTagger.Artwork {
        val isPng = picture.size > 3 && picture[0] == 0x89.toByte() && picture[1] == 'P'.code.toByte()
        if (!isPng) return MediaTagger.Artwork(picture, "image/jpeg")
        val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size) ?: return MediaTagger.Artwork(picture, "image/png")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return MediaTagger.Artwork(out.toByteArray(), "image/jpeg")
    }
}
