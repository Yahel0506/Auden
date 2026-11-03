package dev.auden

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.BuildConfig
import dev.auden.dj.voice.DjClipRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders one real Spanish line through Fish Audio on the device, end to end: the request, the PCM
 * decode, the WAV writer, the loudness envelope. It spends a few credits per run, so it is skipped
 * unless a FISH_API_KEY is in the build (a git-ignored .env at the repo root, see .env.example).
 */
@RunWith(AndroidJUnit4::class)
class DjVoiceDeviceTest {
    @Test
    fun rendersASpanishLineOnThePhone() {
        assumeTrue("no FISH_API_KEY in this build", BuildConfig.FISH_API_KEY.isNotBlank())
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = runBlocking {
            DjClipRenderer(context).render(
                "es",
                "Buenas noches. Vamos con algo tranquilo, de los noventa.",
                seed = 42,
            )
        }
        assertNotNull(clip)
        assertTrue("clip too short: ${clip!!.durationMs} ms", clip.durationMs > 1_000)
        assertTrue("silent clip", clip.envelope.any { it > 0f })
        // Left on disk so the run can be pulled and checked on the computer.
        java.io.File(context.getExternalFilesDir(null), "dj-test.wav").apply { parentFile?.mkdirs() }
            .also { clip.file.copyTo(it, overwrite = true) }
    }
}
