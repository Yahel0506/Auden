package com.music.bitchord.download

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.LocalPlaylist
import dev.auden.library.LocalPlaylists
import dev.auden.transfer.DownloadedCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Downloads what the playlists downloaded whole gain in the library, while
 * [AppSettings.syncDownloadedPlaylists] is on.
 *
 * Only the library is looked at — never YouTube Music or Spotify. A playlist
 * gains songs when the listener adds them or asks for a refresh from its source
 * ([dev.auden.transfer.PlaylistRefresher]); [LocalPlaylists.onSongsAdded] says so
 * straight away, and the periodic check catches up on what could not be fetched
 * then (no Wi-Fi, a batch waiting for approval, a failed download). A YouTube
 * Music playlist downloaded before it had a library copy is followed through
 * that copy ([dev.auden.transfer.DownloadedCopies]); until it has one, it is
 * left as it is.
 *
 * What is new is worked out fresh on every check and nothing about it is stored
 * until it has been dealt with. That is what makes a check safe to lose at any
 * point: Android stopping the job or the process dying leave the remaining
 * tracks new, and the next check simply finds them again.
 *
 * Only ever adds. A track the listener deleted is still named by its playlist's
 * record, so it does not come back; a track removed from the playlist keeps its file.
 */
object PlaylistSync {

    private const val TAG = "BitChord"
    private const val PERIODIC_WORK = "playlist_sync"
    private const val ONE_OFF_WORK = "playlist_sync_now"
    private const val PREFS = "bitchord_playlist_sync"
    private const val KEY_LAST_CHECK = "last_complete_check"
    private const val CHANNEL_ID = "playlist_updates"
    internal const val KEY_APPROVED = "approved"

    /** Android batches this with whatever else is waking the phone in the last [FLEX_HOURS]. */
    private const val PERIOD_HOURS = 12L
    private const val FLEX_HOURS = 4L

    /** How stale the last whole check may be when the app comes to the front. */
    private const val CATCH_UP_MILLIS = 6L * 60 * 60 * 1000

    /** The most one playlist may fetch without being asked; about 1.7 GB of lossless. */
    internal const val ASK_ABOVE = 50

    /** How long a check waits for the library to be read before giving up until the next one. */
    private const val LIBRARY_LOAD_MILLIS = 30_000L

    /** One check at a time, however many of the triggers fire together. */
    private val running = Mutex()

    /** Lets a burst of edits to a device playlist finish before its new songs are looked at. */
    private const val LOCAL_SETTLE_MILLIS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Follows the setting and the Wi-Fi rule its schedule depends on.
     *
     * Off, this never touches WorkManager, which is initialised on demand for
     * that reason: a switch that defaults to off should cost a launch nothing.
     */
    fun init(app: Application) {
        // A device playlist gaining songs (added by hand, or by a refresh from its source
        // the listener asked for) is looked at straight away rather than at the next check.
        LocalPlaylists.onSongsAdded = { id -> localPlaylistChanged(app, id) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var wasOn: Boolean? = null
            AppSettings.syncDownloadedPlaylists
                .combine(AppSettings.wifiOnlyDownloads) { on, wifiOnly -> on to wifiOnly }
                .distinctUntilChanged()
                .collect { (on, wifiOnly) ->
                    val firstLook = wasOn == null
                    if (on) {
                        schedule(app, wifiOnly, replace = !firstLook)
                        if (wasOn == false) checkNow(app)
                    } else if (!firstLook) {
                        cancel(app)
                    }
                    wasOn = on
                }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (AppSettings.syncDownloadedPlaylists.value && catchUpDue(app)) checkNow(app)
            }
        })
    }

    /**
     * Queues a check to run as soon as its conditions allow.
     *
     * @param approved also fetches the batches over [ASK_ABOVE], and replaces a
     *   check still waiting so that the answer is not lost to one that won't.
     */
    fun checkNow(context: Context, approved: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
            .setConstraints(constraints(AppSettings.wifiOnlyDownloads.value))
            .setInputData(workDataOf(KEY_APPROVED to approved))
            .build()
        val policy = if (approved) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF_WORK, policy, request)
    }

    internal fun cancel(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(ONE_OFF_WORK)
    }

    private fun schedule(context: Context, wifiOnly: Boolean, replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            PERIOD_HOURS,
            TimeUnit.HOURS,
            FLEX_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints(wifiOnly)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    private fun catchUpDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) >= CATCH_UP_MILLIS

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- A check ------------------------------------------------------------

    /**
     * Goes through every downloaded playlist in turn, against the library only:
     * no request is made, so a check always runs to its end and is stamped.
     */
    internal suspend fun check(context: Context, approved: Boolean) {
        // An approval waits its turn. Anything else would only repeat the check
        // already running.
        if (approved) running.lock() else if (!running.tryLock()) return
        try {
            if (!storageWritable(context)) return
            // A check woken with the process must not take a library still loading for an empty one.
            withTimeoutOrNull(LIBRARY_LOAD_MILLIS) { LocalPlaylists.awaitLoaded() } ?: return
            Downloads.collections.value.keys.forEach { id ->
                if (!AppSettings.syncDownloadedPlaylists.value || !AppSettings.downloadsAllowedNow) return
                val record = Downloads.collections.value[id] ?: return@forEach
                // Nothing to ask anyone: the playlist is here, and so is its listing.
                val device = devicePlaylistOf(record) ?: return@forEach
                update(context, record, Listing(downloadable(device.songs)), approved)
            }
            prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        } finally {
            running.unlock()
        }
    }

    /**
     * A playlist on this device just gained songs. If it was downloaded whole, the new ones are
     * downloaded too; otherwise this does nothing. Only that playlist is looked at, with no
     * request to anyone.
     */
    internal fun localPlaylistChanged(context: Context, playlistId: String) {
        if (!AppSettings.syncDownloadedPlaylists.value) return
        val app = context.applicationContext
        val recordIds = recordsOf(playlistId)
        if (recordIds.isEmpty()) return
        scope.launch {
            delay(LOCAL_SETTLE_MILLIS)
            running.withLock {
                if (!AppSettings.syncDownloadedPlaylists.value || !AppSettings.downloadsAllowedNow) return@withLock
                if (!storageWritable(app)) return@withLock
                for (recordId in recordIds) {
                    val record = Downloads.collections.value[recordId] ?: continue
                    val device = devicePlaylistOf(record) ?: continue
                    update(app, record, Listing(downloadable(device.songs)), approved = false)
                }
            }
        }
    }

    /**
     * The download records behind library playlist [playlistId]: its own, and that of the
     * YouTube Music playlist it is the copy of, if that was downloaded before it had a copy.
     */
    internal fun recordsOf(playlistId: String): List<String> {
        val id = playlistId.removePrefix(Downloads.PLAYLIST_PREFIX)
        val own = Downloads.pageIdFor(id)
        val source = LocalPlaylists.get(id)?.sourceId
        return Downloads.collections.value.values
            .filter { record -> record.id == own || source != null && DownloadedCopies.copySourceId(record) == source }
            .map { it.id }
    }

    /**
     * The library playlist a downloaded record stands for: one made here or a copy, by its own id; and for a
     * YouTube Music playlist downloaded under its YouTube id, its library copy (none until it has one).
     */
    internal fun devicePlaylistOf(record: SavedCollection): LocalPlaylist? =
        if (record.playlist && record.id.startsWith(Downloads.PLAYLIST_PREFIX + LocalPlaylists.ID_PREFIX)) {
            LocalPlaylists.get(record.id)
        } else {
            DownloadedCopies.copySourceId(record)?.let(LocalPlaylists::bySource)
        }

    /** Files already on the device have nothing to download, and nothing to be named by. */
    internal fun downloadable(songs: List<Song>): List<Song> =
        songs.filter { it.videoId.isNotBlank() && it.localUri == null && it.localPath == null }

    private suspend fun update(context: Context, record: SavedCollection, listing: Listing, approved: Boolean) {
        val fresh = newTracks(listing.songs, record)
        val notification = record.id.hashCode()
        if (fresh.isEmpty()) {
            notifications(context)?.cancel(notification)
            return
        }
        if (fresh.size > ASK_ABOVE && !approved) {
            post(context, notification, askNotification(context, notification, record.title, fresh.size))
            return
        }
        // Already on the device for some other reason: joins the playlist
        // without being fetched again.
        val saved = Downloads.saved.value
        val (onDevice, missing) = fresh.partition { it.videoId in saved }
        val cover = listing.cover ?: record.thumbnailUrl?.takeIf { it.startsWith("http") }
        val fetch = missing.map { song ->
            if (song.thumbnailUrl.isNullOrBlank() && cover != null) song.copy(thumbnailUrl = cover) else song
        }
        val handled = onDevice.mapTo(HashSet()) { it.videoId } + Downloads.downloadNow(context, fetch, record.title)

        // Read again: the listener may have deleted this download meanwhile, and
        // writing the record back would bring it back from the dead.
        val current = Downloads.collections.value[record.id] ?: return
        val order = listingToRecord(listing.songs, current, handled)
        Downloads.rememberCollection(
            DownloadTarget(
                id = current.id,
                title = current.title,
                subtitle = current.subtitle,
                thumbnailUrl = current.thumbnailUrl,
                playlist = true,
            ),
            order,
        )
        val arrived = missing.count { it.videoId in handled && it.videoId in Downloads.saved.value }
        if (arrived > 0) post(context, notification, doneNotification(context, record.title, arrived))
    }

    /**
     * Below Android 10 an exported download needs a permission only a window can
     * ask for, and every track would fail for the want of it.
     */
    private fun storageWritable(context: Context): Boolean =
        !AppSettings.exportDownloads.value || !DownloadStore.needsLegacyPermission() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    // ---- A playlist's listing -----------------------------------------------

    /** A playlist's songs as the library has them, in order. [cover] fills in a song with no artwork. */
    internal class Listing(val songs: List<Song>, val cover: String? = null)

    /**
     * The YouTube Music playlist a record was downloaded from, under the id YouTube
     * answers to: a playlist is filed under whichever id it was downloaded from,
     * with or without its `VL`. Its library copy is found by it
     * ([DownloadedCopies.copySourceId]); it is never read from YouTube here.
     *
     * Null for an album, and for the two kinds of playlist with no YouTube copy:
     * Spotify's own and those kept on this device.
     */
    internal fun syncableBrowseId(record: SavedCollection): String? {
        val id = record.id
        return when {
            !record.playlist || ':' in id || id.startsWith("MP") -> null
            id.startsWith("VL") -> id
            else -> "VL$id"
        }
    }

    /** The tracks in [listing] that [record] has never named. */
    internal fun newTracks(listing: List<Song>, record: SavedCollection): List<Song> {
        val known = record.videoIds.toHashSet()
        return listing.filter { it.videoId !in known }
    }

    /**
     * [listing] without whatever is still to be dealt with, in the playlist's
     * own order. A track left out stays new, so the next check tries it again.
     */
    internal fun listingToRecord(listing: List<Song>, record: SavedCollection, handled: Set<String>): List<Song> {
        val known = record.videoIds.toHashSet()
        return listing.filter { it.videoId in known || it.videoId in handled }
    }

    // ---- Notifications ------------------------------------------------------

    private fun notifications(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun post(context: Context, id: Int, notification: Notification) {
        val manager = notifications(context) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playlist_updates_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "playlist update not posted: ${it.message}") }
    }

    private fun askNotification(context: Context, id: Int, playlist: String, count: Int): Notification {
        val approve = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, PlaylistSyncApproval::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(context)
            .setContentTitle(context.getString(R.string.playlist_sync_ask_title, playlist, count))
            .setContentText(context.getString(R.string.playlist_sync_ask_text))
            .setContentIntent(approve)
            .build()
    }

    private fun doneNotification(context: Context, playlist: String, count: Int): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return builder(context)
            .setContentTitle(context.getString(R.string.playlist_sync_done_title, playlist, count))
            .setContentIntent(open)
            .build()
    }

    private fun builder(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setAutoCancel(true)
}

/** The scheduled half of [PlaylistSync]; every trigger ends up here. */
class PlaylistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Turned off in a run of the app that died before it could cancel this.
        if (!AppSettings.syncDownloadedPlaylists.value) {
            PlaylistSync.cancel(applicationContext)
            return Result.success()
        }
        PlaylistSync.check(applicationContext, inputData.getBoolean(PlaylistSync.KEY_APPROVED, false))
        // Never a retry: a check that could not run is due again when the app
        // next opens, and a download that keeps failing would otherwise have
        // WorkManager retrying it for as long as the setting stays on.
        return Result.success()
    }
}

/** The tap on a "new songs in this playlist" notification. */
class PlaylistSyncApproval : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaylistSync.checkNow(context, approved = true)
    }
}
