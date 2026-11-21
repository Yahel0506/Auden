package dev.auden.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.ui.components.CoverCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The way to a profile photo: the gallery, then a cropper to frame it in the round it is worn in, then the saved photo.
 * Returns what to call to start it; the cropper is drawn by this call as a dialog over whatever is on screen, so it
 * stands wherever the caller does. [onBusy] is true while the framed photo is being written, [onFailed] when it could
 * not be read or saved.
 */
@Composable
fun rememberProfilePhotoPicker(onBusy: (Boolean) -> Unit = {}, onFailed: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentBusy by rememberUpdatedState(onBusy)
    val currentFailed by rememberUpdatedState(onFailed)
    var picked by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked = uri
    }
    picked?.let { uri ->
        CoverCropper(
            uri = uri,
            outputSide = ProfilePhoto.CROP_SIDE,
            circular = true,
            title = R.string.profile_crop_title,
            onCancel = { picked = null },
            onFailed = {
                picked = null
                currentFailed()
            },
            onCropped = { framed ->
                picked = null
                scope.launch {
                    currentBusy(true)
                    try {
                        val saved = withContext(Dispatchers.IO) { ProfilePhoto.save(context, framed) }
                        if (saved != null) ProfileStore.setPhoto(saved) else currentFailed()
                    } finally {
                        currentBusy(false)
                    }
                }
            },
        )
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
