@file:OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)

package com.music.bitchord.ui.components

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.YtPlaylistSync
import dev.auden.library.PlaylistMerge
import dev.auden.library.Service
import dev.auden.transfer.PlaylistRefresher
import dev.auden.transfer.RefreshError
import dev.auden.transfer.RefreshException
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The two decisions a playlist's menu asks for: bringing it up to date from where it came from, and sending it to
 * YouTube Music. Each is a request held here, so the screen that opens the menu only sets it and
 * [TransferDialogsHost] — one line in the page — draws the alert.
 */
object TransferDialogs {
    data class Refresh(val playlistId: String, val title: String)

    /** [browseId] is the playlist's `local:playlist:` page, as [YtPlaylistSync] takes it. */
    data class Export(val browseId: String, val title: String)

    val refresh = MutableStateFlow<Refresh?>(null)
    val export = MutableStateFlow<Export?>(null)
}

@Composable
fun TransferDialogsHost(hazeState: HazeState) {
    val refresh by TransferDialogs.refresh.collectAsStateWithLifecycle()
    val export by TransferDialogs.export.collectAsStateWithLifecycle()
    refresh?.let { RefreshAlert(hazeState, it) { TransferDialogs.refresh.value = null } }
    export?.let { ExportAlert(hazeState, it) { TransferDialogs.export.value = null } }
}

@Composable
private fun AlertBody(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun serviceName(service: Service): String =
    stringResource(serviceLabelRes(service))

private fun serviceName(context: android.content.Context, service: Service): String =
    context.getString(serviceLabelRes(service))

/** Why a refresh could not be made, in words — for the alert and for the toast that follows a refresh that ran. */
private fun refreshErrorText(context: android.content.Context, error: RefreshError?): String = when (error) {
    null -> context.getString(R.string.refresh_error_generic)
    RefreshError.NotFound -> context.getString(R.string.refresh_error_gone)
    RefreshError.NoOrigin -> context.getString(R.string.refresh_error_no_origin)
    RefreshError.LikesList -> context.getString(R.string.refresh_error_likes_list)
    RefreshError.NoListing -> context.getString(R.string.refresh_error_no_listing)
    is RefreshError.NotConnected -> context.getString(R.string.refresh_error_not_connected, serviceName(context, error.service))
    is RefreshError.Expired -> context.getString(R.string.refresh_error_expired, serviceName(context, error.service))
    is RefreshError.EmptySource -> context.getString(R.string.refresh_error_empty, serviceName(context, error.service))
    is RefreshError.Failed -> context.getString(R.string.refresh_error_failed, serviceName(context, error.service))
}

/** "Update from origin": what the source holds now against the copy, with a button for each way to apply it. */
@Composable
private fun RefreshAlert(hazeState: HazeState, request: TransferDialogs.Refresh, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val refresher = remember { PlaylistRefresher.get(context) }
    val preview by produceState<Result<PlaylistRefresher.Preview>?>(null, request.playlistId) {
        value = refresher.preview(request.playlistId)
    }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        val result = preview
        when {
            result == null -> AlertBody(request.title, stringResource(R.string.refresh_reading))
            result.isFailure -> {
                val reason = (result.exceptionOrNull() as? RefreshException)?.reason
                AlertBody(request.title, refreshErrorText(context, reason))
                AlertGap()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
            }
            else -> {
                val p = result.getOrThrow()
                val updated = p.syncedAt?.let {
                    stringResource(R.string.refresh_updated, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                } ?: stringResource(R.string.refresh_never_updated)
                val source = serviceName(p.origin.service)
                val body = buildString {
                    append(updated).append(" · ").append(source)
                    if (p.firstRefresh) append("\n").append(stringResource(R.string.refresh_first))
                    if (!p.mergeChanges) append("\n").append(stringResource(R.string.refresh_nothing_new))
                    if (p.unmatched.isNotEmpty()) append("\n").append(stringResource(R.string.refresh_unmatched, p.unmatched.size))
                }
                AlertBody(request.title, body)
                // A first update with nothing to change is still offered: it is what records where the source stands,
                // so what is removed here afterwards stays removed.
                if (p.mergeChanges || p.firstRefresh) {
                    AlertGap()
                    AlertAction(
                        label = if (p.mergeChanges) stringResource(R.string.refresh_merge, p.merge.added, p.merge.removed)
                        else stringResource(R.string.refresh_mark_synced),
                        emphasised = true,
                        onClick = animatedExit { start(context, refresher, request, PlaylistMerge.Mode.MERGE, onDismiss) },
                    )
                }
                if (p.replaceChanges) {
                    AlertGap()
                    AlertAction(
                        label = stringResource(R.string.refresh_replace, p.replace.added, p.replace.removed),
                        emphasised = false,
                        onClick = animatedExit { start(context, refresher, request, PlaylistMerge.Mode.REPLACE, onDismiss) },
                    )
                }
                AlertGap()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
            }
        }
    }
}

private fun start(
    context: android.content.Context,
    refresher: PlaylistRefresher,
    request: TransferDialogs.Refresh,
    mode: PlaylistMerge.Mode,
    onDismiss: () -> Unit,
) {
    onDismiss()
    refresher.start(request.playlistId, mode) { result ->
        val message = result.fold(
            onSuccess = { context.getString(R.string.refresh_done, request.title) },
            onFailure = { refreshErrorText(context, (it as? RefreshException)?.reason) },
        )
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

/** "Export to YouTube Music": what it would do there — made new, or updated — with what would be removed named. */
@Composable
private fun ExportAlert(hazeState: HazeState, request: TransferDialogs.Export, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<Result<YtPlaylistSync.ExportPreview>?>(null, request.browseId) {
        value = YtPlaylistSync.preview(context, request.browseId)
    }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        val result = preview
        when {
            result == null -> AlertBody(request.title, stringResource(R.string.refresh_reading))
            result.isFailure -> {
                AlertBody(request.title, result.exceptionOrNull()?.message ?: stringResource(R.string.export_yt_failed))
                AlertGap()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
            }
            else -> {
                val p = result.getOrThrow()
                val s = p.summary
                val body = buildString {
                    if (s.create) {
                        append(stringResource(R.string.export_yt_create, s.total))
                    } else {
                        append(stringResource(R.string.export_yt_update, s.add, s.remove))
                        if (p.removed.isNotEmpty()) {
                            append("\n").append(stringResource(R.string.export_yt_removes))
                            p.removed.take(5).forEach { append("\n· ").append(it.title) }
                            if (p.removed.size > 5) append("\n… +").append(p.removed.size - 5)
                        }
                    }
                    if (s.skipped > 0) append("\n").append(stringResource(R.string.yt_sync_skipped, s.skipped))
                }
                AlertBody(stringResource(R.string.export_yt_title, request.title), body)
                AlertGap()
                AlertAction(
                    label = stringResource(R.string.export_yt_confirm),
                    emphasised = true,
                    onClick = animatedExit {
                        onDismiss()
                        YtPlaylistSync.start(context, request.browseId)
                    },
                )
                AlertGap()
                AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
            }
        }
    }
}
