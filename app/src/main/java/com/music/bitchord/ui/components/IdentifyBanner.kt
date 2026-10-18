package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import dev.auden.library.db.AudenDb
import dev.auden.library.ident.TrackIdentifier

/**
 * The Local Music page's way into identification: one quiet card that says how
 * many files have not been looked at, starts the run, shows its progress, and
 * opens the list of suggestions that need a yes or no. Absent once there is
 * nothing left to do, so a fully identified library has no banner at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = remember { AudenDb.get(context).tracks() }
    val unidentified by dao.observeUnidentifiedCount().collectAsStateWithLifecycle(initialValue = 0)
    val review by dao.observeReview().collectAsStateWithLifecycle(initialValue = emptyList())
    val state by TrackIdentifier.state.collectAsStateWithLifecycle()
    var reviewing by remember { mutableStateOf(false) }

    if (!state.running && unidentified == 0 && review.isEmpty()) return

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (state.running || unidentified > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.running) { TrackIdentifier.start(context) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.identify_songs),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.running) {
                            stringResource(R.string.identifying_progress, state.done, state.total)
                        } else {
                            stringResource(R.string.identify_unidentified_count, unidentified)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.running && state.total > 0) {
                // Taken once, here: the run resets to 0 of 0 the instant it ends, a frame before
                // this bar leaves, and 0f / 0 is NaN, which the progress bar refuses with a crash.
                val fraction = (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
            }
        }
        if (review.isNotEmpty()) {
            if (state.running || unidentified > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { reviewing = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.RateReview, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.identify_review, review.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // The sheet has nothing left to show once the last suggestion is answered.
    LaunchedEffect(review.isEmpty()) { if (review.isEmpty()) reviewing = false }

    if (reviewing) {
        ModalBottomSheet(
            onDismissRequest = { reviewing = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.review_matches_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { TrackIdentifier.acceptAll(context) }) { Text(stringResource(R.string.accept_all)) }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(review, key = { it.id }) { track ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(track.artist, track.album).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(
                                    R.string.suggestion_label,
                                    listOfNotNull(track.suggestedTitle, track.suggestedArtist).joinToString(" — "),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundAction(Icons.Rounded.Close, stringResource(R.string.reject_match)) {
                            TrackIdentifier.reject(context, track.id)
                        }
                        Spacer(Modifier.width(8.dp))
                        RoundAction(Icons.Rounded.Check, stringResource(R.string.accept_match), filled = true) {
                            TrackIdentifier.accept(context, track.id)
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 22.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
