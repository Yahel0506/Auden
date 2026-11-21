package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.playback.PlayerBinding
import com.music.bitchord.playback.QueueScreenUndo
import com.music.bitchord.playback.clearUserQueueWithUndo
import com.music.bitchord.playback.removeQueueItemWithUndo
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.undoQueueEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The queue screen's removals and their Undo, kept out of the app's root composable
 * (which is at the JVM's method-size limit): the calls that remove with an inverse
 * recorded, and the notice that offers it.
 */
private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Removes the queue row at [index], and offers to put it back. */
fun MediaController.removeQueueRowWithUndo(index: Int) {
    val song = runCatching { getMediaItemAt(index).toSong() }.getOrNull() ?: return
    undoScope.launch { removeQueueItemWithUndo(index, song) }
}

/** Clears Next in queue, and offers to put it back. */
fun MediaController.clearQueueWithUndo() {
    undoScope.launch { clearUserQueueWithUndo() }
}

/** Shows [QueueScreenUndo]'s notice with its Undo button, over the foot of the queue. */
@Composable
fun QueueUndoNoticeSlot() {
    val controller = PlayerBinding.controller
    val undo by QueueScreenUndo.notice.collectAsStateWithLifecycle()
    var id by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<QueueActionNotice?>(null) }
    LaunchedEffect(undo) {
        notice = undo?.let { QueueActionNotice(++id, it.message) }
    }
    QueueActionNoticeWithAction(
        notice = notice,
        modifier = Modifier.navigationBarsPadding(),
        actionLabel = stringResource(R.string.queue_undo_action),
        onAction = {
            val token = undo?.token
            if (controller != null && token != null) undoScope.launch { controller.undoQueueEdit(token) }
        },
    )
}
