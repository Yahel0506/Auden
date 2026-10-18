package dev.auden.library

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Copies cloud playlists and albums (YouTube Music, Spotify) into the device
 * library, reporting progress on a system notification as it goes.
 *
 * Runs in its own scope rather than a screen's or a view model's, so leaving
 * the page it was started from does not abandon it, and says how it is doing
 * through [saving] (for the in-app buttons) and the notification (for
 * everything else) instead of a transient message that has to be read in time.
 */
object CloudSaver {
    private const val CHANNEL_ID = "library_save"
    private const val MATCH_PARALLELISM = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _saving = MutableStateFlow<Set<String>>(emptySet())

    /** Source ids being saved right now. */
    val saving: StateFlow<Set<String>> = _saving.asStateFlow()

    sealed interface Result {
        data class Saved(val name: String, val added: Int, val created: Boolean, val unmatched: Int) : Result
        data object Failed : Result
    }

    /** The key a cloud page is saved under: its browse id without YouTube's `VL` prefix. */
    fun sourceId(browseId: String) = browseId.removePrefix("VL")

    fun save(context: Context, browseId: String, title: String, thumbnailUrl: String?, onDone: (Result) -> Unit = {}) {
        val source = sourceId(browseId)
        if (source in _saving.value) return
        _saving.value = _saving.value + source
        val app = context.applicationContext
        scope.launch {
            val notifier = Notifier(app, source, title)
            notifier.reading()
            val result = runCatching { run(browseId, source, title, thumbnailUrl, notifier) }
                .getOrElse { Result.Failed }
            when (result) {
                is Result.Saved -> notifier.done(result)
                Result.Failed -> notifier.failed()
            }
            _saving.value = _saving.value - source
            onDone(result)
        }
    }

    private suspend fun run(browseId: String, source: String, title: String, thumbnailUrl: String?, notifier: Notifier): Result {
        var unmatched = 0
        val songs: List<Song> = if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            val tracks = SpotifyLibrary.tracks(browseId.removePrefix(SPOTIFY_PAGE_PREFIX)) { }
            val gate = Semaphore(MATCH_PARALLELISM)
            val done = AtomicInteger()
            val matched = coroutineScope {
                tracks.map { track ->
                    async {
                        gate.withPermit {
                            val found = runCatching { SpotifyImporter.matchTrack(track) }.getOrNull()
                                ?.let { it.copy(thumbnailUrl = it.thumbnailUrl ?: track.imageUrl) }
                            notifier.matching(done.incrementAndGet(), tracks.size)
                            found
                        }
                    }
                }.awaitAll()
            }
            unmatched = matched.count { it == null }
            matched.filterNotNull()
        } else {
            val isAlbum = browseId.startsWith("MPREb") || browseId.startsWith("OLAK")
            YtMusicRepository.allSongs(browseId).getOrThrow().map { song ->
                song.copy(
                    albumName = song.albumName ?: title.takeIf { isAlbum },
                    thumbnailUrl = song.thumbnailUrl ?: thumbnailUrl,
                )
            }
        }
        require(songs.isNotEmpty()) { "nothing to save" }
        notifier.storing(songs.size)
        val saved = LocalPlaylists.saveFromSource(source, title, songs)
        return Result.Saved(saved.playlist.title, saved.added, saved.created, unmatched)
    }

    /** One save's notification: an ongoing progress bar, then a plain note of how it ended. */
    private class Notifier(private val context: Context, source: String, private val title: String) {
        private val id = 0x9100 + (source.hashCode() and 0xFFF)
        private var lastUpdate = 0L

        init { ensureChannel(context) }

        fun reading() = post(builder(context.getString(R.string.saving_reading)).setProgress(0, 0, true))

        fun matching(done: Int, total: Int) {
            // Dozens of updates a second would only make the shade stutter.
            val now = System.currentTimeMillis()
            if (done < total && now - lastUpdate < 400) return
            lastUpdate = now
            post(builder(context.getString(R.string.saving_matching, done, total)).setProgress(total, done, false))
        }

        fun storing(count: Int) = post(builder(context.getString(R.string.saving_storing, count)).setProgress(0, 0, true))

        fun done(result: Result.Saved) {
            val text = when {
                result.unmatched > 0 -> context.getString(R.string.saved_text_unmatched, result.added, result.unmatched)
                result.created -> context.getString(R.string.saved_text, result.added)
                else -> context.getString(R.string.synced_text, result.added)
            }
            post(
                finished(context.getString(R.string.saved_title, result.name), text),
            )
        }

        fun failed() = post(
            finished(context.getString(R.string.save_failed_title, title), context.getString(R.string.save_failed_text)),
        )

        private fun builder(text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.saving_title, title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())

        private fun finished(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())

        private fun openApp() = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        @SuppressLint("MissingPermission")
        private fun post(builder: NotificationCompat.Builder) {
            // Without the permission the in-app buttons still show the state; nothing else to do.
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.library_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.library_channel_description)
                setShowBadge(false)
            },
        )
    }
}
