package dev.auden.transfer

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.download.Downloads
import dev.auden.library.LocalPlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The one-time moves that make Auden's library its own, run once per launch until each is done, after start-up has
 * settled: the YouTube Music account's likes into Auden's ([AccountLikesMigration]), and downloaded YouTube Music
 * playlists into library copies ([DownloadedCopies]). Started from the Application, never from a screen.
 *
 * Nothing here writes to YouTube Music or Spotify.
 */
object TransferMigrations {
    private const val TAG = "AudenTransfer"
    private const val PREFS = "auden_transfer"
    private const val KEY_LIKES_ATTEMPTS = "account_likes_attempts"
    private const val KEY_COPY_ATTEMPTS = "copy_attempts:"

    /** Past this many unfinished runs the likes are left to "Transfer your music" rather than tried every launch. */
    private const val MAX_LIKES_ATTEMPTS = 20
    private const val SETTLE_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    /** Where the account likes stand, for "Transfer your music" to show. */
    sealed interface LikesState {
        /** Not looked at yet this launch, or there is no account to read. */
        data object Idle : LikesState
        data class Running(val progress: ImportProgress?) : LikesState
        data object Done : LikesState
        /** Not finished; tried again next launch while [attempts] is under the limit. */
        data class Incomplete(val attempts: Int, val gaveUp: Boolean) : LikesState
    }

    private val _accountLikes = MutableStateFlow<LikesState>(LikesState.Idle)
    val accountLikes: StateFlow<LikesState> = _accountLikes.asStateFlow()

    /** The import engine over the app's own stores, for these migrations and for "Transfer your music". */
    fun engine(context: Context): ImportEngine =
        ImportEngine(AndroidImportLibrary(context), AndroidImportResolver(context), YtMusicSource())

    /**
     * Starts the moves still to be done. [signedIn] and [session] are read when they run: [session] names the
     * active account and profile, so a switch midway stops the likes rather than mixing two accounts.
     */
    fun start(context: Context, signedIn: () -> Boolean, session: () -> String?) {
        val app = context.applicationContext
        scope.launch {
            delay(SETTLE_MS)
            if (!running.tryLock()) return@launch
            try {
                runCatching { accountLikes(app, signedIn, session) }
                    .onFailure { Log.w(TAG, "account likes: ${it.message}") }
                runCatching { downloadedCopies(app) }
                    .onFailure { Log.w(TAG, "downloaded copies: ${it.message}") }
            } finally {
                running.unlock()
            }
        }
    }

    private suspend fun accountLikes(app: Context, signedIn: () -> Boolean, session: () -> String?) {
        if (OfflinePlaylists.accountLikesImported()) {
            _accountLikes.value = LikesState.Done
            return
        }
        if (!signedIn()) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val attempts = prefs.getInt(KEY_LIKES_ATTEMPTS, 0)
        if (attempts >= MAX_LIKES_ATTEMPTS) {
            _accountLikes.value = LikesState.Incomplete(attempts, gaveUp = true)
            return
        }
        val started = session()
        // The profile the session acts as, before Liked Music is asked for: otherwise it may be the account's
        // default channel's likes rather than the chosen profile's.
        Innertube.ensureSessionScope()
        _accountLikes.value = LikesState.Running(null)
        val migration = AccountLikesMigration(
            engine = engine(app),
            source = YtMusicSource(),
            state = object : AccountLikesMigration.State {
                override fun done() = OfflinePlaylists.accountLikesImported()
                override fun markDone() = OfflinePlaylists.flush(markAccountLikesImported = true)
            },
            sameSession = { signedIn() && session() == started },
        )
        when (val outcome = migration.run { _accountLikes.value = LikesState.Running(it) }) {
            AccountLikesMigration.Outcome.AlreadyDone -> _accountLikes.value = LikesState.Done
            is AccountLikesMigration.Outcome.Completed -> {
                Log.d(TAG, "account likes imported: ${outcome.report.likes}")
                _accountLikes.value = LikesState.Done
            }
            is AccountLikesMigration.Outcome.Incomplete -> {
                prefs.edit().putInt(KEY_LIKES_ATTEMPTS, attempts + 1).apply()
                Log.w(TAG, "account likes not finished (${outcome.report.failed}); ${outcome.report.likes} so far")
                _accountLikes.value = LikesState.Incomplete(attempts + 1, gaveUp = attempts + 1 >= MAX_LIKES_ATTEMPTS)
            }
        }
    }

    private suspend fun downloadedCopies(app: Context) {
        val records = Downloads.collections.value.values
        if (DownloadedCopies.remote(records).isEmpty()) return
        LocalPlaylists.awaitLoaded()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = DownloadedCopies.convert(
            engine = engine(app),
            source = YtMusicSource(),
            records = records,
            metadata = Downloads.savedMetadata.value,
            library = AndroidImportLibrary(app),
            attempts = { sourceId ->
                val failed = prefs.getInt(KEY_COPY_ATTEMPTS + sourceId, 0) + 1
                prefs.edit().putInt(KEY_COPY_ATTEMPTS + sourceId, failed).apply()
                failed
            },
        )
        Log.d(TAG, "downloaded playlists: $result")
    }
}
