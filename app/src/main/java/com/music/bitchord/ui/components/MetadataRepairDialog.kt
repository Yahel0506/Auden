package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.download.MetadataRepair
import com.music.bitchord.download.MetadataRepair.Gap
import com.music.bitchord.download.MetadataRepair.Outcome
import com.music.bitchord.download.MetadataRepair.Reason
import com.music.bitchord.download.MetadataRepair.SongResult
import com.music.bitchord.download.MetadataRepair.Step
import dev.chrisbanes.haze.HazeState

/**
 * The metadata repair as it happens, in the app's frosted alert: which song it is
 * on and what it is doing to it, and once it is over, the full summary — counts,
 * what was filled in, and why each song that could not be repaired was left.
 */
@Composable
fun MetadataRepairDialog(hazeState: HazeState) {
    val session by MetadataRepair.session.collectAsStateWithLifecycle()
    val s = session ?: return
    AlertScaffold(
        hazeState = hazeState,
        onDismiss = { if (s.finished || s.offer != null) MetadataRepair.dismiss() },
    ) {
        when {
            s.offer != null -> Offer(s.offer!!)
            s.finished -> Summary(s)
            else -> Running(s)
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Offer(items: List<MetadataRepair.Accepted>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Header(
        stringResource(R.string.metadata_repair_offer_title),
        stringResource(R.string.metadata_repair_offer_text, items.size),
    )
    Column(
        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            Text(
                text = listOfNotNull(item.title, item.artist).joinToString(" — ").ifBlank { item.song.title },
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    AlertRule()
    AlertAction(
        label = stringResource(R.string.metadata_repair_action),
        emphasised = true,
        onClick = { MetadataRepair.startOffered(context) },
    )
    AlertRule()
    AlertAction(label = stringResource(R.string.metadata_repair_not_now), emphasised = false, onClick = animatedExit { MetadataRepair.dismiss() })
}

@Composable
private fun Running(s: MetadataRepair.Session) {
    Header(stringResource(R.string.metadata_repair_title), stringResource(R.string.metadata_repair_progress, s.done, s.total))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
        LinearProgressIndicator(
            progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
            modifier = Modifier.fillMaxWidth(),
        )
        s.current?.let { song ->
            Text(
                text = song.title,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(song.artist.takeIf { it.isNotBlank() }, s.step?.let { stepLabel(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.results.takeLast(5).reversed().forEach { ResultLine(it) }
        }
    }
    AlertRule()
    AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = { MetadataRepair.cancel() })
}

@Composable
private fun Summary(s: MetadataRepair.Session) {
    val r = s.report
    Header(
        title = stringResource(if (s.cancelled) R.string.metadata_repair_stopped else R.string.metadata_repair_finished),
        subtitle = if (r.checked == 0) stringResource(R.string.metadata_repair_nothing)
        else stringResource(R.string.metadata_repair_checked, r.checked),
    )
    if (r.checked > 0) {
        AlertRule()
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatRow(stringResource(R.string.metadata_repair_repaired), r.repaired)
            StatRow(stringResource(R.string.metadata_repair_complete), r.complete)
            StatRow(stringResource(R.string.metadata_repair_unavailable), r.unavailable)
            if (r.repaired > 0) {
                Text(
                    text = stringResource(R.string.metadata_repair_filled),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (r.covers > 0) StatRow(stringResource(R.string.metadata_repair_gap_cover), r.covers)
                if (r.titles > 0) StatRow(stringResource(R.string.metadata_repair_gap_title), r.titles)
                if (r.artists > 0) StatRow(stringResource(R.string.metadata_repair_gap_artist), r.artists)
                if (r.albums > 0) StatRow(stringResource(R.string.metadata_repair_gap_album), r.albums)
            }
            val failed = s.results.filter { it.outcome == Outcome.UNAVAILABLE }
            if (failed.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.metadata_repair_left),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                failed.forEach { ResultLine(it, detailed = true) }
            }
        }
    }
    AlertRule()
    AlertAction(label = stringResource(android.R.string.ok), emphasised = true, onClick = animatedExit { MetadataRepair.dismiss() })
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ResultLine(result: SongResult, detailed: Boolean = false) {
    val mark = when (result.outcome) {
        Outcome.REPAIRED -> "✓"
        Outcome.COMPLETE -> "•"
        else -> "–"
    }
    val filledLabels = result.filled.map { gapLabel(it) }
    val detail = when (result.outcome) {
        Outcome.REPAIRED -> filledLabels.joinToString(", ")
        Outcome.UNAVAILABLE -> result.reason?.let { reasonLabel(it) }.orEmpty()
        else -> ""
    }
    Column {
        Text(
            text = "$mark ${result.title}",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotEmpty() && (detailed || result.outcome == Outcome.REPAIRED)) {
            Text(
                text = detail,
                modifier = Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun stepLabel(step: Step): String = stringResource(
    when (step) {
        Step.READING -> R.string.metadata_repair_step_reading
        Step.LOOKING_UP -> R.string.metadata_repair_step_lookup
        Step.DOWNLOADING_COVER -> R.string.metadata_repair_step_cover
        Step.WRITING -> R.string.metadata_repair_step_writing
        Step.CHECKING -> R.string.metadata_repair_step_checking
    },
)

@Composable
private fun gapLabel(gap: Gap): String = stringResource(
    when (gap) {
        Gap.COVER -> R.string.metadata_repair_gap_cover
        Gap.TITLE -> R.string.metadata_repair_gap_title
        Gap.ARTIST -> R.string.metadata_repair_gap_artist
        Gap.ALBUM -> R.string.metadata_repair_gap_album
    },
)

@Composable
private fun reasonLabel(reason: Reason): String = stringResource(
    when (reason) {
        Reason.UNREADABLE -> R.string.metadata_repair_why_unreadable
        Reason.NO_MATCH -> R.string.metadata_repair_why_no_match
        Reason.NO_COVER -> R.string.metadata_repair_why_no_cover
        Reason.WRITE_FAILED -> R.string.metadata_repair_why_write
        Reason.UNSUPPORTED -> R.string.metadata_repair_why_unsupported
    },
)
