package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import dev.chrisbanes.haze.HazeState

/** A delete waiting for the listener's yes: what to say, and what to do on it. */
class DeleteRequest(val title: String, val message: String, val onConfirm: () -> Unit)

/**
 * The confirmation every delete button opens, in the app's own frosted alert: title
 * and what will happen, then Cancel and Delete side by side, Delete in the system
 * red. Drawn in the activity's layer above the whole UI — the menu that raised it
 * has to be closed first — over a screen that is dimmed and only softly blurred.
 */
@Composable
fun FrostedDeleteConfirm(
    hazeState: HazeState,
    request: DeleteRequest,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val red = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color(0xFFFF3B30) else Color(0xFFFF453A)
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, width = 300.dp, blurBackdrop = true) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = request.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = request.message,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
        AlertRule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            AlertAction(
                label = stringResource(R.string.cancel),
                emphasised = false,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Column(
                Modifier.width(0.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
            ) {}
            AlertAction(
                label = stringResource(R.string.delete),
                emphasised = true,
                onClick = { onDismiss(); request.onConfirm() },
                modifier = Modifier.weight(1f),
                color = red,
            )
        }
    }
}
