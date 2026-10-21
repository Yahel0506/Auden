package dev.auden

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.auden.dj.voice.DjClipRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders one real line with a voice pack on the device, end to end: the sherpa-onnx engine, the
 * espeak data, the WAV writer, the loudness shape. Skipped unless the pack is already on the device
 * (a debug build's files can be seeded with run-as), so a plain instrumented run never downloads.
 */
@RunWith(AndroidJUnit4::class)
class DjVoiceDeviceTest {
    @Test
    fun rendersAnEnglishLineOnThePhone() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val pack = dev.auden.dj.voice.VoicePacks.forLanguage("en")
        assertNotNull(pack)
        val folder = java.io.File(context.filesDir, "dj/voices/${pack!!.id}")
        assumeTrue("voice pack not on the device", java.io.File(folder, ".installed").exists())
        val clip = runBlocking {
            DjClipRenderer(context).render(
                "en",
                "Good evening. Let's go with something easy, from the nineties.",
                seed = 42,
            )
        }
        assertNotNull(clip)
        assertTrue("clip too short: ${clip!!.durationMs} ms", clip.durationMs > 1_500)
        assertTrue("silent clip", clip.envelope.any { it > 0.1f })
        // Left on disk so the run can be pulled and checked on the computer.
        java.io.File(context.getExternalFilesDir(null), "dj-test.wav").apply { parentFile?.mkdirs() }
            .also { clip.file.copyTo(it, overwrite = true) }
    }
}
