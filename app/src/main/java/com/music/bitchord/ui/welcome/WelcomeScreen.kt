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

private val Ink = Color(0xFF000000)
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
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == 3
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // Page one plays the intro and then the logo's loop; the others share the loop of drops alone. The two
        // cross-fade with the swipe.
        val position = pager.currentPage + pager.currentPageOffsetFraction
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp).align(Alignment.TopCenter)) {
            WelcomeVideo(
                intro = R.raw.welcome_intro, loop = R.raw.welcome_logo_loop, play = playing,
                modifier = Modifier.graphicsLayer { alpha = (1f - position).coerceIn(0f, 1f) },
            )
            WelcomeVideo(
                intro = null, loop = R.raw.welcome_drops_loop, play = playing,
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
                            .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.3f)),
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
        Text(title, color = Color.White, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
        Spacer(Modifier.height(12.dp))
        Text(body, color = Color.White.copy(alpha = 0.62f), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
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

@Composable
private fun IconGrid() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon.entries.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { WelcomeIcon(it) }
                repeat(5 - row.size) { Spacer(Modifier.size(52.dp)) }
            }
        }
    }
}

@Composable
private fun WelcomeIcon(option: AppIcon) {
    val preview = option.previewRes
    if (preview == null) {
        AndroidView(
            factory = { ctx -> android.widget.ImageView(ctx).apply { setImageResource(option.iconRes) } },
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)),
        )
    } else {
        Image(painterResource(preview), null, Modifier.size(52.dp).scale(1.5f), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun AccentRow() {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spectrum.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { c ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.55f))))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
