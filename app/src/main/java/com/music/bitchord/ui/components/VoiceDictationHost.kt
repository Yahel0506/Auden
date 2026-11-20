package com.music.bitchord.ui.components

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Gives every search field under [content] the phone's own dictation: the microphone beside the magnifier opens the
 * system speech recognizer and the words it hears come back as the query. With no recognizer installed there is no
 * microphone.
 */
@Composable
fun VoiceDictationHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) pending?.invoke(heard)
        pending = null
    }
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    val available = remember(context) { intent.resolveActivity(context.packageManager) != null }
    val dictation: VoiceDictation? = if (available) {
        { onText ->
            pending = onText
            runCatching { launcher.launch(Intent(intent)) }.onFailure { pending = null }
        }
    } else null
    CompositionLocalProvider(LocalVoiceDictation provides dictation, content = content)
}
