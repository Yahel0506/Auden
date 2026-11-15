package com.music.bitchord.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.music.bitchord.data.model.Song

/**
 * The app's one multi-selection of songs, as a row sees it.
 *
 * Every list of tracks is drawn from [SongRow], so the row itself asks this
 * rather than each screen passing the same four things down: while a selection
 * is on, a tap ticks the row instead of playing it, the hold and the swipe
 * step aside, and the ⋮ gives its place to the tick.
 */
@Stable
interface SongSelection {
    /** Whether a selection is being made — true from the first pick until it is closed, even with nothing ticked. */
    val selecting: Boolean

    fun isSelected(song: Song): Boolean

    fun toggle(song: Song)

    /**
     * What "Select all" means on the list on screen: told by the list, since
     * only it knows what a filter or a sort left standing.
     */
    fun offer(visible: List<Song>)
}

/** Null where nothing hosts a selection — a preview, the desktop app. */
val LocalSongSelection = staticCompositionLocalOf<SongSelection?> { null }

/**
 * What tells two rows apart in a selection: the playlist entry where there is
 * one, since a playlist may hold the same song twice, and the song otherwise.
 */
val Song.selectionKey: String get() = setVideoId ?: videoId
