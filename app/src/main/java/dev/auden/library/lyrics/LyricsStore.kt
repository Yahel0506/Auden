package dev.auden.library.lyrics

import android.content.Context
import com.music.bitchord.data.lyrics.LyricsArtifact
import com.music.bitchord.data.lyrics.LyricsArtifactFormat
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import dev.auden.library.db.AudenDb
import dev.auden.library.db.LyricsVariantEntity
import dev.auden.library.db.SongLyricsPrefEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The lyrics kept for each song, one per provider, and which one the listener chose to see.
 *
 * Lives beside the lyrics already written into downloaded files (tags and `.lrc`), not instead of them:
 * those are for other players, this is what lets Auden keep several candidates, switch between them
 * offline and remember the choice and its timing for a song that was only ever streamed.
 */
object LyricsStore {
    private val _changes = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The song key of every song whose stored lyrics or choice just changed, so whatever is showing it can look again. */
    val changes: kotlinx.coroutines.flow.SharedFlow<String> = _changes

    /** What a song is filed under: its id, which for a file with no id is its uri. */
    fun key(song: Song): String = song.videoId

    fun keyOf(song: Song): String = key(song)

    /** A stored variant, ready to show. */
    class Stored(val provider: LyricsSource, val format: LyricsArtifactFormat, val fetchedAt: Long, val result: LyricsRepository.Result) {
        val wordSynced: Boolean get() = result.lines.any { it.isWordSynced }
        val synced: Boolean get() = result.lines.any { it.timeMs > 0 }
    }

    private fun dao(context: Context) = AudenDb.get(context).lyrics()

    suspend fun variants(context: Context, key: String): List<Stored> = withContext(Dispatchers.IO) {
        dao(context).variantsOf(key).mapNotNull(::decode)
    }

    suspend fun save(context: Context, key: String, artifact: LyricsArtifact, makeActive: Boolean = false) = withContext(Dispatchers.IO) {
        val dao = dao(context)
        dao.upsert(LyricsVariantEntity(key, artifact.source.name, artifact.format.name, artifact.content))
        if (makeActive) setActiveNow(context, key, artifact.source)
        _changes.tryEmit(key)
    }

    suspend fun delete(context: Context, key: String, provider: LyricsSource) = withContext(Dispatchers.IO) {
        val dao = dao(context)
        dao.deleteVariant(key, provider.name)
        // A choice of a variant that is gone is no choice.
        val pref = dao.pref(key)
        if (pref?.activeProvider == provider.name) dao.upsertPref(pref.copy(activeProvider = null))
        _changes.tryEmit(key)
    }

    suspend fun activeProvider(context: Context, key: String): LyricsSource? = withContext(Dispatchers.IO) {
        dao(context).pref(key)?.activeProvider?.let { runCatching { LyricsSource.valueOf(it) }.getOrNull() }
    }

    /** The variant to show for [key]: the chosen one if it is still stored, else null. */
    suspend fun active(context: Context, key: String): Stored? = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val provider = dao.pref(key)?.activeProvider ?: return@withContext null
        dao.variant(key, provider)?.let(::decode)
    }

    suspend fun setActive(context: Context, key: String, provider: LyricsSource?) = withContext(Dispatchers.IO) {
        setActiveNow(context, key, provider)
        _changes.tryEmit(key)
    }

    private suspend fun setActiveNow(context: Context, key: String, provider: LyricsSource?) {
        val dao = dao(context)
        val pref = dao.pref(key) ?: SongLyricsPrefEntity(key)
        dao.upsertPref(pref.copy(activeProvider = provider?.name))
    }

    suspend fun offset(context: Context, key: String): Int? = withContext(Dispatchers.IO) { dao(context).pref(key)?.offsetMs }

    /** Sets this song's own lyrics offset, or clears it so the global one applies again. */
    suspend fun setOffset(context: Context, key: String, offsetMs: Int?) = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val pref = dao.pref(key) ?: SongLyricsPrefEntity(key)
        dao.upsertPref(pref.copy(offsetMs = offsetMs))
        _changes.tryEmit(key)
    }

    /**
     * Brings in the lyrics file a download already has beside it, once, so a song downloaded before variants
     * existed shows what it has. It becomes the chosen one only if nothing was chosen.
     */
    suspend fun importExisting(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val uri = com.music.bitchord.download.Downloads.savedLyricsUri(song.videoId) ?: return@withContext
        val provider = com.music.bitchord.download.Downloads.savedLyricsSource(song.videoId)
            ?.let { runCatching { LyricsSource.valueOf(it) }.getOrNull() } ?: return@withContext
        val format = com.music.bitchord.download.Downloads.savedLyricsFormat(song.videoId)
            ?.let { runCatching { LyricsArtifactFormat.valueOf(it) }.getOrNull() } ?: return@withContext
        if (dao(context).variant(key(song), provider.name) != null) return@withContext
        val content = com.music.bitchord.download.LyricsSidecarStore.read(context, android.net.Uri.parse(uri)) ?: return@withContext
        val result = LyricsRepository.offline(content, format, provider) ?: return@withContext
        val artifact = result.artifact ?: return@withContext
        save(context, key(song), artifact, makeActive = dao(context).pref(key(song))?.activeProvider == null)
    }

    private fun decode(entity: LyricsVariantEntity): Stored? {
        val provider = runCatching { LyricsSource.valueOf(entity.provider) }.getOrNull() ?: return null
        val format = runCatching { LyricsArtifactFormat.valueOf(entity.format) }.getOrNull() ?: return null
        val result = LyricsRepository.offline(entity.content, format, provider) ?: return null
        return Stored(provider, format, entity.fetchedAt, result)
    }
}
