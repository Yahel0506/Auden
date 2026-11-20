package com.music.bitchord.ui.screens

import androidx.annotation.StringRes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import com.music.bitchord.ui.icons.SfIcons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/**
 * One suggestion for the card at the top of Settings: something that is not switched on yet and that fits what the
 * listener has been doing, with the page it lives on. After Mochy Music's recommended card (GPLv3).
 */
private class SettingsTip(
    @param:StringRes val title: Int,
    val page: SettingsCategory,
    val applies: Boolean,
    /** Fits right now — on mobile data, say — so it goes ahead of the rest. */
    val timely: Boolean = false,
)

/** Up to [count] suggestions, timely ones first and never two for the same page, so the card stays varied. */
private fun pickTips(count: Int): List<SettingsTip> {
    val metered = AppSettings.meteredConnection.value == true
    val all = listOf(
        SettingsTip(R.string.rec_data, SettingsCategory.AUDIO_QUALITY, applies = metered, timely = true),
        SettingsTip(R.string.rec_wifi_downloads, SettingsCategory.DOWNLOADS, applies = metered && !AppSettings.wifiOnlyDownloads.value, timely = true),
        SettingsTip(R.string.rec_automix, SettingsCategory.PLAYBACK, applies = !AppSettings.smartFadeEnabled.value && AppSettings.crossfadeSeconds.value == 0),
        SettingsTip(R.string.rec_loudness, SettingsCategory.AUDIO_QUALITY, applies = !AppSettings.loudnessNormalization.value),
        SettingsTip(R.string.rec_spatial, SettingsCategory.AUDIO_QUALITY, applies = !AppSettings.spatialAudio.value),
        SettingsTip(R.string.rec_canvas, SettingsCategory.NOW_PLAYING, applies = !AppSettings.animatedCanvas.value),
        SettingsTip(R.string.rec_lyrics, SettingsCategory.LYRICS, applies = !AppSettings.syncedLyrics.value),
        SettingsTip(R.string.rec_dj, SettingsCategory.DJ, applies = !AppSettings.showDjTab.value),
        SettingsTip(R.string.rec_performance, SettingsCategory.MISCELLANEOUS, applies = !AppSettings.highPerformanceMode.value),
        SettingsTip(R.string.rec_backup, SettingsCategory.YOUR_DATA, applies = true),
    ).filter { it.applies }
    val (timely, rest) = all.partition { it.timely }
    return (timely.shuffled() + rest.shuffled()).distinctBy { it.page }.take(count)
}

/**
 * The recommended card, drawn once per visit to the page so a tip does not change under a finger. Set apart from the
 * grouped cards under it: the same fill, an accent outline a little thicker, headed by a lit bulb, and each tip on a filled round chip.
 */
@Composable
internal fun RecommendedSettings(onOpen: (SettingsCategory) -> Unit) {
    val tips = remember { pickTips(3) }
    if (tips.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(24.dp)
    Spacer(Modifier.height(18.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, accent.copy(alpha = 0.32f), shape)
            .padding(bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = SfIcons.Lightbulb,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_recommended),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        tips.forEach { tip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onOpen(tip.page) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tip.page.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(tip.title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.settings_crumb, stringResource(tip.page.title)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron()
            }
        }
    }
}
