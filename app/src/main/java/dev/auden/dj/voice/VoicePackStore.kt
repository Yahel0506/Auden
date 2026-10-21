package dev.auden.dj.voice

import android.content.Context
import android.util.Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Downloads, unpacks and remembers the voice packs.
 *
 * A pack is "installed" only when its marker file exists, which is written last: a download cut
 * short, or an unpack that failed halfway, leaves no marker and is simply tried again. Everything
 * happens off the main thread; the state of each pack is published so the DJ sheet can show it.
 */
object VoicePackStore {
    private const val TAG = "AudenVoice"
    private const val MARKER = ".installed"

    sealed interface State {
        data object Missing : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var root: File

    fun init(context: Context) {
        root = File(context.filesDir, "dj/voices").apply { mkdirs() }
        // Voices of languages the DJ no longer speaks (it is English only) are only taking room.
        scope.launch {
            root.listFiles()?.filter { dir -> VoicePacks.all.none { it.id == dir.name } }?.forEach { it.deleteRecursively() }
        }
        _states.value = VoicePacks.all.associate { it.id to if (isReady(it)) State.Ready else State.Missing }
    }

    /** The folder a pack unpacks into. */
    fun folderOf(pack: VoicePacks.Pack): File = File(root, pack.id)

    fun isReady(pack: VoicePacks.Pack): Boolean = File(folderOf(pack), MARKER).exists()

    /** Starts the download unless the pack is already there or already coming. */
    fun download(pack: VoicePacks.Pack) {
        if (isReady(pack) || _states.value[pack.id] is State.Downloading) return
        scope.launch { fetch(pack) }
    }

    fun remove(pack: VoicePacks.Pack) {
        scope.launch {
            lock.withLock {
                folderOf(pack).deleteRecursively()
                setState(pack, State.Missing)
            }
        }
    }

    private suspend fun fetch(pack: VoicePacks.Pack) = lock.withLock {
        if (isReady(pack)) return@withLock
        setState(pack, State.Downloading(0f))
        val archive = File(root, pack.archiveName + ".part")
        try {
            withContext(Dispatchers.IO) {
                Http.client.newCall(Request.Builder().url(pack.url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = checkNotNull(response.body) { "empty response" }
                    val total = body.contentLength().takeIf { it > 0 } ?: pack.sizeBytes
                    body.byteStream().use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            var lastReported = 0f
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val fraction = (written.toFloat() / total).coerceIn(0f, 1f)
                                if (fraction - lastReported >= 0.01f) {
                                    lastReported = fraction
                                    setState(pack, State.Downloading(fraction))
                                }
                            }
                        }
                    }
                }
                setState(pack, State.Downloading(1f))
                unpack(archive, pack)
            }
            File(folderOf(pack), MARKER).writeText(pack.id)
            setState(pack, State.Ready)
        } catch (e: Exception) {
            Log.w(TAG, "voice ${pack.id} failed", e)
            folderOf(pack).deleteRecursively()
            setState(pack, State.Failed(e.message ?: "download failed"))
        } finally {
            archive.delete()
        }
    }

    /** Extracts the archive under [root]; entries must stay inside it. */
    private fun unpack(archive: File, pack: VoicePacks.Pack) {
        val base = root.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val out = File(root, entry.name).canonicalFile
                check(out.path.startsWith(base.path + File.separator)) { "unsafe entry ${entry.name}" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
        val model = folderOf(pack).listFiles { f -> f.name.endsWith(".onnx") }
        check(!model.isNullOrEmpty()) { "no voice model in ${pack.id}" }
    }

    private fun setState(pack: VoicePacks.Pack, state: State) {
        _states.value = _states.value + (pack.id to state)
    }
}
