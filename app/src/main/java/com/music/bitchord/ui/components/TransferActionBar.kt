package com.music.bitchord.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ui.welcome.GlassPill
import com.music.bitchord.ui.welcome.welcomePalette
import dev.auden.transfer.ImportStage
import dev.auden.transfer.TransferSession

/**
 * The foot of "Transfer your music", made as the update page's download foot is: always at the bottom edge, with a
 * pill that starts the transfer, a progress bar above it while it runs (with the percentage and a way to cancel it),
 * and the same pill to finish or to try again. Drawn over the whole app rather than inside the page, so nothing of
 * the page can sit in front of it or take its touches.
 */
@Composable
fun TransferActionBar() {
    val open by TransferSession.pageOpen.collectAsStateWithLifecycle()
    val state by TransferSession.state.collectAsStateWithLifecycle()
    val ticked by TransferSession.selection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showing = open && (
        state is TransferSession.State.Choosing || state is TransferSession.State.Running ||
            state is TransferSession.State.Done || state is TransferSession.State.Failed
        )
    if (!showing) return
    val p = welcomePalette()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, p.bg.copy(alpha = 0.92f), p.bg)))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                is TransferSession.State.Choosing -> GlassPill(
                    text = if (ticked.count > 0) stringResource(R.string.transfer_start_count, ticked.count) else stringResource(R.string.transfer_start),
                    onClick = { TransferSession.startSelected(context) },
                    enabled = ticked.count > 0,
                )
                is TransferSession.State.Running -> {
                    // The whole transfer's share done, eased as the update's bar is, and the same number as the page's ring.
                    val fraction by animateFloatAsState(s.fraction, spring(dampingRatio = 1f, stiffness = 300f), label = "transfer-bar")
                    TransferProgressBar(fraction)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.transfer_running_percent, (fraction * 100).toInt()),
                            color = p.fg.copy(alpha = 0.7f), fontSize = 14.sp,
                        )
                        Text(
                            stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { TransferSession.cancel() },
                        )
                    }
                    GlassPill(stringResource(R.string.transfer_start), onClick = {}, enabled = false)
                }
                is TransferSession.State.Done -> {
                    TransferProgressBar(1f)
                    GlassPill(stringResource(R.string.transfer_done_button), onClick = { TransferSession.reset() })
                }
                is TransferSession.State.Failed -> GlassPill(
                    stringResource(R.string.transfer_retry),
                    onClick = { TransferSession.open(s.service) },
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun TransferProgressBar(fraction: Float) {
    val p = welcomePalette()
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(p.fg.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(p.fg),
        )
    }
}
