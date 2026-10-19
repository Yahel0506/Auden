package dev.auden.library

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Keeps the library copies of Spotify and YouTube Music playlists and albums up to date by itself.
 *
 * One direction only, and only additive: what the original has gained is added to the copy, and nothing is ever
 * written to Spotify or YouTube Music, nor taken out of the copy. A song removed from a copy therefore comes back
 * on the next sync, the same as with the manual button.
 *
 * It runs when the app comes to the front, at most every [MIN_INTERVAL_MS] per playlist, and not at all on a
 * metered connection unless the listener allowed it. A run reports once, at the end, and only if something new
 * arrived — nobody wants a notification for "nothing changed".
 */
object PlaylistAutoSync {
    private const val TAG = "PlaylistAutoSync"
    private const val PREFS = "auden_autosync"
    private const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val STARTUP_SETTLE_MS = 15_000L
    private const val SUMMARY_NOTIFICATION_ID = 0x9400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Called whenever the app comes to the front; does nothing unless a sync is due. */
    fun onForeground(context: Context) {
        if (!AppSettings.autoSyncPlaylists.value) return
        val app = context.applicationContext
        scope.launch {
            // Let start-up finish first: the library is still loading and the network is busy.
            delay(STARTUP_SETTLE_MS)
            runDue(app)
        }
    }

    /** Syncs every copy whose last sync is older than the interval. */
    private suspend fun runDue(context: Context) {
        if (!lock.tryLock()) return
        try {
            if (!AppSettings.autoSyncPlaylists.value) return
            if (AppSettings.meteredConnection.value == true && !AppSettings.autoSyncOnMobileData.value) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val due = LocalPlaylists.playlists.value.filter { copy ->
                val source = copy.sourceId ?: return@filter false
                now - prefs.getLong(source, 0L) >= MIN_INTERVAL_MS
            }
            if (due.isEmpty()) return
            var added = 0
            var spotifyBlocked = false
            for (copy in due) {
                val source = copy.sourceId!!
                val isSpotify = source.startsWith(SPOTIFY_PAGE_PREFIX)
                // A refused Spotify session fails the same way for every playlist; ask once, not for each.
                if (isSpotify && (spotifyBlocked || !com.music.bitchord.data.spotify.SpotifyLibrary.isSignedIn())) continue
                val result = runCatching { CloudSaver.saveNow(context, source, copy.title, null, quiet = true) }
                    .onFailure { if (it is SpotifyException) spotifyBlocked = true }
                    .getOrNull()
                when (result) {
                    is CloudSaver.Result.Saved -> {
                        prefs.edit().putLong(source, System.currentTimeMillis()).apply()
                        if (!result.created) added += result.added
                    }
                    else -> Log.d(TAG, "could not sync ${copy.title}; will try again later")
                }
                // Gently: a library of fifty playlists must not be fifty requests in a burst.
                delay(1_500)
            }
            if (added > 0) {
                JobNotification(context, SUMMARY_NOTIFICATION_ID, context.getString(R.string.autosync_title)).finish(
                    context.getString(R.string.autosync_title),
                    context.getString(R.string.autosync_done, added),
                )
            }
        } finally {
            lock.unlock()
        }
    }
}
