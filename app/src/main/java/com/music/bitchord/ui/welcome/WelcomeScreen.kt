package com.music.bitchord.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.AppIcon
import com.music.bitchord.R
import kotlinx.coroutines.launch

private val Spectrum = listOf(
    Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFBFE04A), Color(0xFFFFD60A),
    Color(0xFFFF9F0A), Color(0xFFFF453A), Color(0xFFFF6482), Color(0xFFE040C8), Color(0xFFA070FF),
)

/**
 * The page shown over the app after an update that changes its look: black, large type and slow aurora light,
 * with frosted-glass cards — in the manner of Apple Music's own landing page. Paged, ending on a start button.
 */
@Composable
fun WelcomeScreen(playing: Boolean, onDone: () -> Unit) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    // Dark icons on the light page, light ones on the dark.
    com.music.bitchord.ui.theme.SystemBarIcons(dark = p.light)
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == 3
    Box(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // Page one plays the intro and then the logo's loop; the others share the loop of drops alone. The two
        // cross-fade with the swipe.
        val position = pager.currentPage + pager.currentPageOffsetFraction
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp).align(Alignment.TopCenter)) {
            WelcomeVideo(
                intro = p.intro, loop = p.logoLoop, play = playing,
                modifier = Modifier.graphicsLayer { alpha = (1f - position).coerceIn(0f, 1f) },
            )
            WelcomeVideo(
                intro = null, loop = p.dropsLoop, play = playing,
                modifier = Modifier.graphicsLayer { alpha = position.coerceIn(0f, 1f) },
            )
        }
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            Page(page)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(if (i == pager.currentPage) 22.dp else 7.dp, 7.dp)
                            .clip(CircleShape)
                            .background(p.fg.copy(alpha = if (i == pager.currentPage) 0.95f else 0.3f)),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            GlassPill(
                text = stringResource(if (last) R.string.welcome_start else R.string.welcome_next),
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            )
        }
    }
}

@Composable
private fun Page(index: Int) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    val title = stringResource(
        when (index) {
            0 -> R.string.welcome_title_1
            1 -> R.string.welcome_title_2
            2 -> R.string.welcome_title_3
            else -> R.string.welcome_title_4
        },
    )
    val body = stringResource(
        when (index) {
            0 -> R.string.welcome_body_1
            1 -> R.string.welcome_body_2
            2 -> R.string.welcome_body_3
            else -> R.string.welcome_body_4
        },
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 28.dp)
            .padding(bottom = 120.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (index) {
                0 -> Unit
                1 -> LogoCard()
                2 -> IconGrid()
                else -> AccentRow()
            }
        }
        Text(title, color = p.fg, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
        Spacer(Modifier.height(12.dp))
        Text(body, color = p.fg.copy(alpha = 0.62f), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LogoCard() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.mipmap.ic_liquid_glass_foreground), null, Modifier.size(112.dp).scale(1.5f), contentScale = ContentScale.Fit)
        Image(painterResource(R.mipmap.ic_liquid_glass_dark_foreground), null, Modifier.size(112.dp).scale(1.5f), contentScale = ContentScale.Fit)
    }
}

/** The thirteen icons as a staggered wall (4, 5, 4 across, the middle row offset), so it reads as one shape. */
@Composable
private fun IconGrid() {
    val icons = AppIcon.entries
    val rows = listOf(icons.subList(0, 4), icons.subList(4, 9), icons.subList(9, icons.size))
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Five across is the widest row; the rows of four sit centred between its cells.
        val gap = 14.dp
        val cell = ((maxWidth - gap * 4) / 5).coerceAtMost(64.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { WelcomeIcon(it, cell) }
                }
            }
        }
    }
}

@Composable
private fun WelcomeIcon(option: AppIcon, size: androidx.compose.ui.unit.Dp) {
    val preview = option.previewRes
    if (preview == null) {
        AndroidView(
            factory = { ctx -> android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) } },
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)),
        )
    } else {
        Image(painterResource(preview), null, Modifier.size(size).scale(1.5f), contentScale = ContentScale.Fit)
    }
}

/**
 * The accent, shown where it lands: a model of the tab bar whose open tab wears the chosen colour, with the nine
 * colours in one row under it. It cycles on its own until a colour is tapped.
 */
@Composable
private fun AccentRow() {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    var picked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var auto by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(picked) {
        if (picked != null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1_500)
            auto = (auto + 1) % Spectrum.size
        }
    }
    val current = picked ?: auto
    val accent by androidx.compose.animation.animateColorAsState(Spectrum[current], label = "accent")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        // The bar, in miniature.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(36.dp))
                .background(p.fg.copy(alpha = 0.09f))
                .border(0.6.dp, p.fg.copy(alpha = 0.22f), RoundedCornerShape(36.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                com.music.bitchord.ui.icons.BitChordIcons.Home,
                com.music.bitchord.ui.icons.BitChordIcons.TabExplore,
                com.music.bitchord.ui.icons.BitChordIcons.TabLibrary,
                com.music.bitchord.ui.icons.BitChordIcons.TabSearch,
            ).forEachIndexed { i, icon ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(if (i == 0) p.fg.copy(alpha = 0.1f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (i == 0) accent else p.fg.copy(alpha = 0.55f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        // The colours, in one even row that always fits.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val dot = ((maxWidth - gap * (Spectrum.size - 1)) / Spectrum.size).coerceAtMost(34.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Spectrum.forEachIndexed { i, c ->
                    val on = i == current
                    Box(
                        Modifier
                            .size(dot)
                            .graphicsLayer { scaleX = if (on) 1.18f else 1f; scaleY = if (on) 1.18f else 1f }
                            .clip(CircleShape)
                            .background(c)
                            .border(if (on) 2.dp else 0.6.dp, p.fg.copy(alpha = if (on) 0.95f else 0.35f), CircleShape)
                            .clickable { picked = i },
                    )
                }
            }
        }
    }
}

@Composable
internal fun GlassPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val p = com.music.bitchord.ui.welcome.welcomePalette()
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(p.fg.copy(alpha = if (enabled) 1f else 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.bg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
