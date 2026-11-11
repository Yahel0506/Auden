package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import dev.auden.library.LocalPlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps a playlist kept on this device in step with a YouTube Music playlist made
 * from it. The device playlist is the source of truth: the first run creates the
 * YouTube Music playlist and remembers it, and every later run rewrites that
 * playlist so it matches the device one exactly, whatever was changed on the
 * YouTube Music side in between.
 *
 * Only songs that exist on YouTube Music (a real video id) can be sent; the rest
 * are left out and counted.
 */
object YtPlaylistSync {
    private const val PREFS = "yt_playlist_sync"
    private const val KEY = "links"
    private const val BATCH = 50
    private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Device playlist id to the YouTube Music playlist made from it. */
    val links: StateFlow<Map<String, String>> = _links.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Percent done, for every sync in progress. Survives the menu closing. */
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    private val _failures = MutableStateFlow<Map<String, String>>(emptyMap())
    val failures: StateFlow<Map<String, String>> = _failures.asStateFlow()

    private val _skipped = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Songs the last run left out for having no YouTube Music version. */
    val skipped: StateFlow<Map<String, Int>> = _skipped.asStateFlow()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
        _links.value = raw.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
    }

    private fun link(context: Context, localId: String, ytId: String?) {
        _links.update { if (ytId == null) it - localId else it + (localId to ytId) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, _links.value.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    fun start(context: Context, browseId: String) {
        val app = context.applicationContext
        init(app)
        val localId = browseId.removePrefix("local:playlist:")
        if (localId in _progress.value) return
        _failures.update { it - localId }
        _progress.update { it + (localId to 0) }
        scope.launch {
            val result = runCatching { run(app, localId) }
            result.exceptionOrNull()?.let { e ->
                _failures.update { it + (localId to (e.message ?: app.getString(R.string.yt_sync_failed))) }
            }
            _progress.update { it - localId }
        }
    }

    private fun report(localId: String, done: Int, total: Int) {
        _progress.update { it + (localId to (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)) }
    }

    private suspend fun run(app: Context, localId: String) {
        val playlist = LocalPlaylists.get(localId) ?: error(app.getString(R.string.yt_sync_failed))
        val ids = playlist.songs.map { it.videoId }.filter { YOUTUBE_ID.matches(it) }
        _skipped.update { it + (localId to playlist.songs.size - ids.size) }
        if (ids.isEmpty()) error(app.getString(R.string.yt_sync_nothing))

        var ytId = _links.value[localId]
        var existing: List<Pair<String, String>> = emptyList() // (setVideoId, videoId)
        if (ytId != null) {
            val entries = YtMusicRepository.playlistEntries("VL$ytId").getOrNull()
            if (entries != null) {
                existing = entries.mapNotNull { s -> s.setVideoId?.let { it to s.videoId } }
            } else {
                // Unreachable: gone from the account, or just a failed request. Only a
                // library that answers and no longer lists it counts as gone.
                val library = YtMusicRepository.libraryPlaylists().getOrNull()
                    ?: error(app.getString(R.string.yt_sync_failed))
                if (library.any { it.browseId?.removePrefix("VL") == ytId }) error(app.getString(R.string.yt_sync_failed))
                ytId = null
            }
        }

        val addChunks = ids.chunked(BATCH)
        if (ytId == null) {
            val total = addChunks.size + 1
            report(localId, 0, total)
            val created = YtMusicRepository.createPlaylist(playlist.title, PlaylistPrivacy.PRIVATE, addChunks.first())
                .getOrElse { throw IllegalStateException(it.message ?: app.getString(R.string.yt_sync_failed)) }
            link(app, localId, created)
            report(localId, 1, total)
            addChunks.drop(1).forEachIndexed { i, chunk ->
                YtMusicRepository.addToPlaylist(created, chunk).getOrThrow()
                report(localId, i + 2, total)
            }
            return
        }

        if (existing.map { it.second } == ids) {
            YtMusicRepository.renamePlaylist(ytId, playlist.title)
            return
        }
        val removeChunks = existing.chunked(BATCH)
        val total = removeChunks.size + addChunks.size + 1
        var done = 0
        report(localId, done, total)
        for (chunk in removeChunks) {
            YtMusicRepository.removeFromPlaylist(ytId, chunk).getOrThrow()
            report(localId, ++done, total)
        }
        for (chunk in addChunks) {
            YtMusicRepository.addToPlaylist(ytId, chunk).getOrThrow()
            report(localId, ++done, total)
        }
        YtMusicRepository.renamePlaylist(ytId, playlist.title)
    }
}
