package com.music.bitchord.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.bitchord.R
import com.music.bitchord.ui.components.lightweightLiquidGlass
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.welcome.GlassPill
import com.music.bitchord.ui.welcome.WelcomeVideo

/** What the download button at the foot of [UpdateScreen] does; null hides the whole foot (the Settings page). */
class UpdateActions(
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onInstall: () -> Unit,
)

/**
 * The page for an update and for "What's new in this update" in Settings: the logo's intro and loop of drops at the
 * top, the version under it, and the whole changelog in a container with Liquid Glass's look (its film and rim,
 * without its effects). With [actions], a download button stays at the foot with its progress bar above it; the
 * back button stays at the top left either way.
 */
@Composable
fun UpdateScreen(
    version: String,
    notes: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: UpdateActions? = null,
) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    com.music.bitchord.ui.theme.SystemBarIcons(dark = p.light)
    Box(
        modifier
            .fillMaxSize()
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // The video is square; a band of its middle is what shows.
                BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().height(250.dp).clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
                    WelcomeVideo(
                        intro = p.intro, loop = p.logoLoop, play = true,
                        modifier = Modifier.requiredSize(maxWidth * 0.82f), square = false,
                    )
                }
                Text(
                    text = stringResource(R.string.update_screen_version, version),
                    color = p.fg,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
                ChangelogCard(notes)
                Spacer(Modifier.height(24.dp))
            }
            if (actions != null) {
                DownloadFoot(actions)
            }
        }
        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(p.fg.copy(alpha = 0.14f))
                .border(0.6.dp, p.fg.copy(alpha = 0.28f), CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SfIcons.ArrowBack, contentDescription = stringResource(R.string.back), tint = p.fg)
        }
    }
}

/** The changelog's container, in the app's glass without its blur and refraction. */
@Composable
private fun ChangelogCard(notes: String?) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    val shape = RoundedCornerShape(34.dp)
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            // The glass every liquid-glass surface is made of — its film, its rim lit from above and the black line at its
            // ends — without the blur and the lens, which have nothing here to sample.
            .lightweightLiquidGlass(shape, fallbackColor = p.fg.copy(alpha = 0.08f))
            .padding(20.dp),
    ) {
        if (notes.isNullOrBlank()) {
            Text(stringResource(R.string.whats_new_none), color = p.fg.copy(alpha = 0.6f), fontSize = 16.sp)
        } else {
            CompositionLocalProvider(LocalContentColor provides p.fg) {
                RichText(style = RichTextStyle.Default) { Markdown(notes) }
            }
        }
    }
}

/** Always at the foot: the progress bar once a download is on, and the button that starts, finishes or retries it. */
@Composable
private fun DownloadFoot(actions: UpdateActions) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    val state by AppUpdateChecker.download.collectAsStateWithLifecycle()
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
            is AppUpdateChecker.DownloadState.Downloading -> {
                ProgressBar(s.fraction)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.update_screen_downloading, (s.fraction * 100).toInt()),
                        color = p.fg.copy(alpha = 0.7f), fontSize = 14.sp,
                    )
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = actions.onCancel),
                    )
                }
                GlassPill(stringResource(R.string.update_screen_download), onClick = {}, enabled = false)
            }
            is AppUpdateChecker.DownloadState.Ready -> {
                ProgressBar(1f)
                GlassPill(stringResource(R.string.install_now), onClick = actions.onInstall)
            }
            is AppUpdateChecker.DownloadState.Failed -> {
                Text(s.message, color = Color(0xFFFF6B6B), fontSize = 14.sp)
                GlassPill(stringResource(R.string.try_again), onClick = actions.onDownload)
            }
            AppUpdateChecker.DownloadState.Idle ->
                GlassPill(stringResource(R.string.update_screen_download), onClick = actions.onDownload)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
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
