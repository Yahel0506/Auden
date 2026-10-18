package dev.auden.library.lyrics

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The lyrics timing offset in force for the song on screen.
 *
 * Most songs are fine with the one global offset; a song whose lyrics were timed against a different cut
 * (a live take, a radio edit) needs its own. When a song has one, it replaces the global value for that song
 * alone; clearing it hands the song back to the global one.
 */
object SongLyricsOffset {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var key: String? = null

    /** This song's own offset, or null when it follows the global one. */
    private val own = MutableStateFlow<Int?>(null)

    /** Whether the offset sheet is editing this song alone. A song with an offset of its own is always in this mode. */
    private val perSongChosen = MutableStateFlow(false)

    /** What the lyric views should apply: the song's own offset if it has one, else the global one. */
    val effective: StateFlow<Int> = combine(AppSettings.lyricsOffsetMs, own) { global, mine -> mine ?: global }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val perSong: StateFlow<Boolean> = combine(own, perSongChosen) { mine, chosen -> mine != null || chosen }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { LyricsStore.changes.collect { changed -> if (changed == key) reload() } }
    }

    /** The song on screen changed. */
    fun onSongChanged(songKey: String) {
        if (songKey == key) return
        key = songKey
        own.value = null
        perSongChosen.value = false
        scope.launch { reload() }
    }

    private suspend fun reload() {
        val context = appContext ?: return
        val k = key ?: return
        own.value = LyricsStore.offset(context, k)
    }

    /** Applies [value] to this song or to every song, depending on the mode the sheet is in. */
    fun set(value: Int) {
        val context = appContext
        val k = key
        if (perSong.value && context != null && k != null) {
            own.value = value
            scope.launch { LyricsStore.setOffset(context, k, value) }
        } else {
            AppSettings.setLyricsOffsetMs(value)
        }
    }

    /**
     * Switches the sheet between this song and all songs. Turning it on starts the song's offset from what is
     * in force now; turning it off drops the song's own so it follows the global one again.
     */
    fun setPerSong(enabled: Boolean) {
        val context = appContext
        val k = key
        perSongChosen.value = enabled
        if (context == null || k == null) return
        if (enabled) {
            val start = effective.value
            own.value = start
            scope.launch { LyricsStore.setOffset(context, k, start) }
        } else {
            own.value = null
            scope.launch { LyricsStore.setOffset(context, k, null) }
        }
    }
}
