package com.music.bitchord.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.sharedui.resources.ytmusic_logo
import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.ui.screens.TransferChooser
import com.music.bitchord.ui.welcome.GlassPill
import com.music.bitchord.ui.welcome.WelcomePalette
import com.music.bitchord.ui.welcome.WelcomeVideo
import com.music.bitchord.ui.welcome.welcomePalette
import dev.auden.library.Service
import dev.auden.transfer.TransferSession

// The steps, in order. The update's welcome is not one of them: someone new starts here, on their music.
private const val MUSIC = 0
private const val IMPORT = 1
private const val PROFILE = 2
private const val GREETING = 3
private const val PREFERENCES = 4
private const val DONE = 5
private const val STEPS = 6

/** The welcome's accents, closed into a ring for the sweeps drawn with them. */
private val Spectrum = listOf(
    Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFBFE04A), Color(0xFFFFD60A),
    Color(0xFFFF9F0A), Color(0xFFFF453A), Color(0xFFFF6482), Color(0xFFE040C8), Color(0xFFA070FF), Color(0xFF0A84FF),
)

/** The app's one curve: a spring with no bounce. */
private fun <T> settle(visibility: T? = null) = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = visibility)

/**
 * The first-run setup, drawn as the update's welcome is: the logo's clip and its drops playing at the head of a
 * page of large type, frosted cards and one white pill per step. It runs from the listener's music to their
 * profile, a greeting, two preferences and the way in.
 */
@Composable
fun OnboardingScreen(
    initialName: String,
    initialPhoto: String?,
    spotifyConnected: Boolean,
    youtubeConnected: Boolean,
    authInProgress: Boolean,
    smartFade: Boolean,
    reduceBlur: Boolean,
    reduceMotion: Boolean,
    importBusy: Boolean,
    onConnectSpotify: () -> Unit,
    onConnectYoutube: () -> Unit,
    onImportBackup: () -> Unit,
    onImportPlaylist: () -> Unit,
    onRequestMusicPermission: () -> Unit,
    onChoosePhoto: () -> Unit,
    onSaveName: (String) -> Unit,
    onSaveSmartFade: (Boolean) -> Unit,
    onSaveReduceBlur: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(MUSIC) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var photo by rememberSaveable { mutableStateOf(initialPhoto) }
    var pendingService by rememberSaveable { mutableStateOf("") }
    var authWasOpened by rememberSaveable { mutableStateOf(false) }
    // Where Profile's Back leads: the import page when the music came from there, else the choice before it.
    var cameFromImport by rememberSaveable { mutableStateOf(false) }
    fun back(from: Int) = when (from) {
        PROFILE -> if (cameFromImport) IMPORT else MUSIC
        else -> (from - 1).coerceAtLeast(MUSIC)
    }
    LaunchedEffect(initialPhoto) { photo = initialPhoto }
    // A restored backup can bring a name with it; it fills the field until the listener types their own.
    LaunchedEffect(initialName) { if (!nameEdited) name = initialName }
    val p = welcomePalette()
    com.music.bitchord.ui.theme.SystemBarIcons(dark = p.light)
    val context = LocalContext.current
    val transfer by TransferSession.state.collectAsState()
    val selection by TransferSession.selection.collectAsState()
    // A sign-in opened from here comes back here: connected, it goes straight on to choosing what to bring; closed
    // without connecting, it leaves the listener on the sources they were looking at.
    LaunchedEffect(spotifyConnected, youtubeConnected, pendingService, authInProgress) {
        when {
            pendingService == "spotify" && spotifyConnected -> { pendingService = ""; authWasOpened = false; TransferSession.open(Service.SPOTIFY) }
            pendingService == "youtube" && youtubeConnected -> { pendingService = ""; authWasOpened = false; TransferSession.open(Service.YT_MUSIC) }
            authInProgress -> authWasOpened = true
            pendingService.isNotEmpty() && authWasOpened && !authInProgress -> { pendingService = ""; authWasOpened = false }
        }
    }
    val transferRunning = transfer is TransferSession.State.Running
    val locked = importBusy || transferRunning
    val choosing = page == IMPORT && transfer is TransferSession.State.Choosing
    var forward by remember { mutableStateOf(true) }
    fun go(to: Int) { forward = to > page; page = to }
    BackHandler(enabled = locked || page > MUSIC || choosing) {
        if (!locked) {
            if (choosing) TransferSession.reset() else go(back(page))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        // The logo's clip opens the setup and closes it; the drops play behind everything between. They cross-fade,
        // and stand back a little behind the pages that are mostly cards.
        val logo by animateFloatAsState(if (page == MUSIC || page == DONE) 1f else 0f, settle(), label = "logo")
        val dim by animateFloatAsState(if (page == IMPORT || page == PREFERENCES || choosing) 0.5f else 1f, settle(), label = "dim")
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 24.dp).align(Alignment.TopCenter)) {
            WelcomeVideo(
                intro = p.intro, loop = p.logoLoop, play = !reduceMotion,
                modifier = Modifier.graphicsLayer { alpha = logo * dim },
            )
            WelcomeVideo(
                intro = null, loop = p.dropsLoop, play = !reduceMotion,
                modifier = Modifier.graphicsLayer { alpha = (1f - logo) * dim },
            )
        }

        if (choosing) {
            val offer = (transfer as TransferSession.State.Choosing).offer
            TransferChooser(
                offer,
                contentPadding = PaddingValues(bottom = 150.dp),
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg.copy(alpha = 0.92f), p.bg)))
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 36.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassPill(stringResource(R.string.transfer_start_count, selection.count), onClick = { TransferSession.startSelected(context) }, enabled = selection.count > 0)
                QuietButton(stringResource(R.string.onboarding_back), p) { TransferSession.reset() }
            }
        } else {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(settle()) togetherWith fadeOut(settle())
                    } else {
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(settle(IntOffset.VisibilityThreshold)) { it / 3 * dir } + fadeIn(settle())) togetherWith
                            (slideOutHorizontally(settle(IntOffset.VisibilityThreshold)) { -it / 3 * dir } + fadeOut(settle()))
                    }
                },
                label = "onboarding",
                modifier = Modifier.fillMaxSize(),
            ) { step ->
                when (step) {
                    MUSIC -> StepPage(
                        p = p,
                        kicker = stringResource(R.string.setup_welcome_title),
                        title = stringResource(R.string.setup_music_title),
                        body = stringResource(R.string.setup_music_body),
                        footer = {
                            GlassPill(stringResource(R.string.setup_bring_music), onClick = { go(IMPORT) })
                            OutlinePill(stringResource(R.string.setup_start_fresh), p) { cameFromImport = false; go(PROFILE) }
                        },
                    )
                    IMPORT -> ImportStep(
                        p = p,
                        transfer = transfer,
                        spotifyConnected = spotifyConnected,
                        youtubeConnected = youtubeConnected,
                        reduceMotion = reduceMotion,
                        onSpotify = { if (spotifyConnected) TransferSession.open(Service.SPOTIFY) else { pendingService = "spotify"; onConnectSpotify() } },
                        onYoutube = { if (youtubeConnected) TransferSession.open(Service.YT_MUSIC) else { pendingService = "youtube"; onConnectYoutube() } },
                        onBackup = onImportBackup,
                        onPlaylist = onImportPlaylist,
                        onPhone = onRequestMusicPermission,
                        onContinue = { TransferSession.reset(); cameFromImport = true; go(PROFILE) },
                    )
                    PROFILE -> {
                        val focus = LocalFocusManager.current
                        val submit = {
                            val n = name.trim()
                            if (n.isNotEmpty()) { focus.clearFocus(); name = n; onSaveName(n); go(GREETING) }
                        }
                        StepPage(
                            p = p,
                            title = stringResource(R.string.setup_profile_title),
                            body = stringResource(R.string.setup_profile_body),
                            illustration = { Avatar(name, photo, p, maxSize = 150.dp, badge = true, onClick = onChoosePhoto) },
                            content = {
                                NameField(name, p, onChange = { nameEdited = true; name = it.take(40) }, onDone = submit)
                            },
                            footer = {
                                GlassPill(stringResource(R.string.onboarding_next), onClick = submit, enabled = name.isNotBlank())
                                QuietButton(stringResource(if (photo == null) R.string.setup_photo_optional else R.string.setup_change_photo), p, onChoosePhoto)
                            },
                        )
                    }
                    GREETING -> StepPage(
                        p = p,
                        title = stringResource(R.string.setup_greeting, name.trim()),
                        body = stringResource(R.string.setup_greeting_body),
                        illustration = { Greeting(name, photo, p, reduceMotion) },
                        footer = { GlassPill(stringResource(R.string.onboarding_next), onClick = { go(PREFERENCES) }) },
                    )
                    PREFERENCES -> StepPage(
                        p = p,
                        title = stringResource(R.string.setup_preferences_title),
                        body = stringResource(R.string.setup_preferences_body),
                        content = {
                            GlassGroup(p) {
                                PreferenceRow(
                                    p, SfIcons.Waves, listOf(Color(0xFFB57BFF), Color(0xFF6E3CF5)),
                                    stringResource(R.string.setup_automix), stringResource(R.string.setup_automix_sub),
                                    smartFade, onSaveSmartFade,
                                )
                                GroupDivider(p)
                                PreferenceRow(
                                    p, SfIcons.BlurOff, listOf(Color(0xFF5BE0D0), Color(0xFF128C9E)),
                                    stringResource(R.string.setup_reduce_blur), stringResource(R.string.setup_reduce_blur_sub),
                                    reduceBlur, onSaveReduceBlur,
                                )
                            }
                        },
                        footer = { GlassPill(stringResource(R.string.onboarding_next), onClick = { go(DONE) }) },
                    )
                    else -> StepPage(
                        p = p,
                        title = stringResource(R.string.setup_done_title, name.trim()),
                        body = stringResource(R.string.setup_done_body),
                        footer = { GlassPill(stringResource(R.string.setup_enter_auden), onClick = onFinish) },
                    )
                }
            }
            TopBar(
                p = p,
                step = page,
                canGoBack = page > MUSIC && !locked,
                onBack = { go(back(page)) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        AnimatedVisibility(importBusy, enter = fadeIn(settle()), exit = fadeOut(settle()), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(p.bg.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                val shape = RoundedCornerShape(28.dp)
                Column(
                    Modifier.clip(shape).background(p.bg).border(0.6.dp, p.fg.copy(alpha = 0.18f), shape)
                        .padding(horizontal = 32.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SpinningRing(p, 44.dp, reduceMotion)
                    Text(stringResource(R.string.transfer_loading), color = p.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Back on the left, and the run of steps as the welcome's dots: the one you are on drawn long. */
@Composable
private fun TopBar(p: WelcomePalette, step: Int, canGoBack: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp)) {
        val backAlpha by animateFloatAsState(if (canGoBack) 1f else 0f, settle(), label = "back")
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .graphicsLayer { alpha = backAlpha }
                .clip(CircleShape)
                .background(p.fg.copy(alpha = 0.1f))
                .border(0.6.dp, p.fg.copy(alpha = 0.2f), CircleShape)
                .clickable(enabled = canGoBack, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SfIcons.ArrowBack, contentDescription = stringResource(R.string.onboarding_back), tint = p.fg, modifier = Modifier.size(20.dp))
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(STEPS) { i ->
                val on = i == step
                val w by animateFloatAsState(if (on) 22f else 7f, settle(), label = "dot")
                Box(
                    Modifier
                        .size(w.dp, 7.dp)
                        .clip(CircleShape)
                        .background(p.fg.copy(alpha = if (on) 0.95f else if (i < step) 0.55f else 0.22f)),
                )
            }
        }
    }
}

/**
 * One step, laid out as a page of the welcome: an illustration in the room left above, then the large title and its
 * line, whatever the step asks for, and its pill at the foot. Without an illustration the words sit at the foot of
 * the page and scroll when they need more than it has.
 */
@Composable
private fun StepPage(
    p: WelcomePalette,
    title: String,
    body: String,
    kicker: String? = null,
    illustration: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .padding(top = 60.dp),
    ) {
        if (illustration != null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) { illustration() }
        }
        Column(
            Modifier
                .then(if (illustration == null) Modifier.weight(1f).wrapContentHeight(Alignment.Bottom) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
        ) {
            if (kicker != null) {
                Text(kicker, color = p.fg.copy(alpha = 0.62f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }
            Text(title, color = p.fg, fontSize = 42.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
            Spacer(Modifier.height(12.dp))
            Text(body, color = p.fg.copy(alpha = 0.62f), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))
            content()
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = footer,
        )
    }
}

/** Where the music comes from, the transfer as it runs, and what it brought. */
@Composable
private fun ImportStep(
    p: WelcomePalette,
    transfer: TransferSession.State,
    spotifyConnected: Boolean,
    youtubeConnected: Boolean,
    reduceMotion: Boolean,
    onSpotify: () -> Unit,
    onYoutube: () -> Unit,
    onBackup: () -> Unit,
    onPlaylist: () -> Unit,
    onPhone: () -> Unit,
    onContinue: () -> Unit,
) {
    AnimatedContent(transfer, contentKey = { it::class }, transitionSpec = { fadeIn(settle()) togetherWith fadeOut(settle()) }, label = "import") { state ->
        when (state) {
            is TransferSession.State.Loading -> StepPage(
                p = p,
                title = stringResource(R.string.setup_transfer_reading_title),
                body = stringResource(R.string.transfer_loading),
                illustration = { SpinningRing(p, 96.dp, reduceMotion) },
                footer = { Spacer(Modifier.height(58.dp)) },
            )
            is TransferSession.State.Running -> StepPage(
                p = p,
                title = stringResource(R.string.setup_transfer_running_title),
                body = stringResource(R.string.setup_transfer_wait),
                // Read live rather than from the frame that started the page, so the ring follows the transfer.
                illustration = { ProgressRing((transfer as? TransferSession.State.Running)?.fraction ?: 1f, p) },
                footer = { Spacer(Modifier.height(58.dp)) },
            )
            is TransferSession.State.Done -> {
                val r = state.report
                val unmatched = r.likes.unmatched + r.playlists.unmatched + r.albums.unmatched + r.artists.unmatched
                StepPage(
                    p = p,
                    title = stringResource(R.string.setup_transfer_done),
                    body = stringResource(R.string.setup_transfer_done_body),
                    illustration = { Badge(SfIcons.Check, listOf(Color(0xFF5EF08A), Color(0xFF1FA85A)), reduceMotion) },
                    content = {
                        if (unmatched > 0 || r.failed.isNotEmpty()) {
                            GlassGroup(p) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconTile(SfIcons.Warning, listOf(Color(0xFFFFC15A), Color(0xFFFF8A1F)))
                                    Spacer(Modifier.width(14.dp))
                                    Text(stringResource(R.string.setup_transfer_pending, unmatched, r.failed.size), color = p.fg.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 20.sp)
                                }
                            }
                        }
                    },
                    footer = {
                        GlassPill(stringResource(R.string.onboarding_next), onClick = onContinue)
                        QuietButton(stringResource(R.string.setup_import_more), p) { TransferSession.reset() }
                    },
                )
            }
            is TransferSession.State.Failed -> StepPage(
                p = p,
                title = stringResource(R.string.setup_transfer_failed_title),
                body = stringResource(R.string.transfer_failed),
                illustration = { Badge(SfIcons.Warning, listOf(Color(0xFFFFB35A), Color(0xFFFF5A3C)), reduceMotion) },
                footer = {
                    GlassPill(stringResource(R.string.try_again), onClick = { TransferSession.open(state.service) })
                    QuietButton(stringResource(R.string.onboarding_back), p) { TransferSession.reset() }
                },
            )
            else -> StepPage(
                p = p,
                title = stringResource(R.string.setup_import_title),
                body = stringResource(R.string.setup_import_body),
                content = {
                    val connected = stringResource(R.string.transfer_connected)
                    val connect = stringResource(R.string.setup_import_service)
                    GlassGroup(p) {
                        SourceRow(p, stringResource(R.string.setup_spotify), if (spotifyConnected) connected else connect, spotifyConnected, onSpotify) {
                            LogoTile(org.jetbrains.compose.resources.painterResource(Res.drawable.spotify_logo), listOf(Color(0xFF3BE477), Color(0xFF169C46)))
                        }
                        GroupDivider(p)
                        SourceRow(p, stringResource(R.string.setup_youtube), if (youtubeConnected) connected else connect, youtubeConnected, onYoutube) {
                            LogoTile(org.jetbrains.compose.resources.painterResource(Res.drawable.ytmusic_logo), listOf(Color(0xFFFF4E45), Color(0xFFD5001C)))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassGroup(p) {
                        SourceRow(p, stringResource(R.string.setup_backup), stringResource(R.string.setup_backup_sub), onClick = onBackup) {
                            IconTile(SfIcons.History, listOf(Color(0xFF6CC8FF), Color(0xFF2F7DF6)))
                        }
                        GroupDivider(p)
                        SourceRow(p, stringResource(R.string.setup_playlist_file), stringResource(R.string.setup_playlist_sub), onClick = onPlaylist) {
                            IconTile(SfIcons.PlaylistPlay, listOf(Color(0xFFFFC56B), Color(0xFFFF8048)))
                        }
                        GroupDivider(p)
                        SourceRow(p, stringResource(R.string.setup_phone_music), stringResource(R.string.setup_phone_music_sub), onClick = onPhone) {
                            IconTile(SfIcons.MusicNote, listOf(Color(0xFFFF9BB3), Color(0xFFFF2D6B)))
                        }
                    }
                },
                footer = { GlassPill(stringResource(R.string.setup_continue), onClick = onContinue) },
            )
        }
    }
}

// ---- Pieces ----

/** Frosted, as the welcome's tab-bar model is: a veil of the type's colour with a hairline edge. */
@Composable
private fun GlassGroup(p: WelcomePalette, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.fg.copy(alpha = if (p.light) 0.07f else 0.1f))
            .border(0.6.dp, p.fg.copy(alpha = 0.2f), shape),
        content = content,
    )
}

@Composable
private fun GroupDivider(p: WelcomePalette) {
    Box(Modifier.fillMaxWidth().padding(start = 70.dp).height(0.6.dp).background(p.fg.copy(alpha = 0.12f)))
}

@Composable
private fun SourceRow(
    p: WelcomePalette,
    title: String,
    subtitle: String,
    done: Boolean = false,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.fg.copy(alpha = 0.58f), fontSize = 14.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF30D158)), contentAlignment = Alignment.Center) {
                Icon(SfIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        } else {
            Icon(SfIcons.ChevronRight, null, tint = p.fg.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PreferenceRow(
    p: WelcomePalette,
    icon: ImageVector,
    colors: List<Color>,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, colors)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.fg.copy(alpha = 0.58f), fontSize = 14.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF30D158),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = p.fg.copy(alpha = 0.18f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A rounded square in a two-stop gradient with a white glyph, as the Library's folders wear theirs. */
@Composable
private fun IconTile(icon: ImageVector, colors: List<Color>, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.linearGradient(colors, Offset.Zero, Offset.Infinite)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun LogoTile(logo: Painter, colors: List<Color>, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.linearGradient(colors, Offset.Zero, Offset.Infinite)),
        contentAlignment = Alignment.Center,
    ) {
        Image(logo, null, Modifier.size(size * 0.56f), contentScale = ContentScale.Fit)
    }
}

/** The second choice of a step, under its white pill: the same shape, drawn as glass. */
@Composable
private fun OutlinePill(text: String, p: WelcomePalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(29.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(p.fg.copy(alpha = 0.1f))
            .border(0.6.dp, p.fg.copy(alpha = 0.24f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QuietButton(text: String, p: WelcomePalette, onClick: () -> Unit) {
    Text(
        text,
        color = p.fg.copy(alpha = 0.7f),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun NameField(name: String, p: WelcomePalette, onChange: (String) -> Unit, onDone: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = name,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(p.fg),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(color = p.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(shape).background(p.fg.copy(alpha = if (p.light) 0.07f else 0.1f))
                    .border(0.6.dp, p.fg.copy(alpha = 0.2f), shape).padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (name.isEmpty()) Text(stringResource(R.string.setup_name_hint), color = p.fg.copy(alpha = 0.4f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                inner()
            }
        },
    )
}

/** The listener's picture, or their initial on the drops' colours, in a ring of the accents. */
@Composable
private fun Avatar(
    name: String,
    photo: String?,
    p: WelcomePalette,
    maxSize: Dp,
    badge: Boolean = false,
    ringTurn: Float = 0f,
    onClick: (() -> Unit)? = null,
) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val size = minOf(maxSize, maxHeight * 0.9f, maxWidth)
        val tap = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Box(Modifier.size(size)) {
            Canvas(Modifier.fillMaxSize()) {
                rotate(ringTurn) {
                    drawCircle(Brush.sweepGradient(Spectrum), radius = this.size.minDimension / 2f - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(9.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF6CC8FF), Color(0xFF8C5CF6), Color(0xFFFF6482)), Offset.Zero, Offset.Infinite))
                    .then(tap),
                contentAlignment = Alignment.Center,
            ) {
                val initial = name.trim().take(1).uppercase()
                when {
                    photo != null -> AsyncImage(dev.auden.profile.ProfilePhoto.model(photo), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    initial.isNotEmpty() -> Text(initial, color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
                    else -> Icon(SfIcons.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
                }
            }
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(size * 0.28f)
                        .clip(CircleShape)
                        .background(p.fg)
                        .border(3.dp, p.bg, CircleShape)
                        .then(tap),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (photo == null) SfIcons.Add else SfIcons.Pencil, null, tint = p.bg, modifier = Modifier.size(size * 0.13f))
                }
            }
        }
    }
}

/** The greeting: the avatar rising in on a soft spring, its ring turning slowly, a glow behind it. */
@Composable
private fun Greeting(name: String, photo: String?, p: WelcomePalette, reduceMotion: Boolean) {
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)) }
    val turn = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = entrance.value
            val s = 0.82f + 0.18f * entrance.value
            scaleX = s; scaleY = s
            translationY = (1f - entrance.value) * 60.dp.toPx()
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.55f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF8C5CF6).copy(alpha = if (p.light) 0.22f else 0.38f), Color.Transparent), center = center, radius = r),
                radius = r,
            )
        }
        Avatar(name, photo, p, maxSize = 190.dp, ringTurn = turn)
    }
}

/** A finished or failed transfer's mark: a glyph in a gradient disc that springs in. */
@Composable
private fun Badge(icon: ImageVector, colors: List<Color>, reduceMotion: Boolean) {
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, settle()) }
    Box(
        Modifier.size(132.dp).graphicsLayer { scaleX = entrance.value; scaleY = entrance.value; alpha = entrance.value }
            .clip(CircleShape).background(Brush.linearGradient(colors, Offset.Zero, Offset.Infinite)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(60.dp))
    }
}

/** The transfer's real progress as a ring in the accents, its percentage at the centre. */
@Composable
private fun ProgressRing(fraction: Float, p: WelcomePalette) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), settle(), label = "progress")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(p.fg.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            rotate(-90f) {
                drawArc(Brush.sweepGradient(Spectrum), 0f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text("${(shown * 100).toInt()}%", color = p.fg, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp)
    }
}

@Composable
private fun SpinningRing(p: WelcomePalette, size: Dp, reduceMotion: Boolean) {
    val turn = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "spin").value
    }
    Canvas(Modifier.size(size)) {
        val stroke = this.size.width / 9f
        val inset = stroke / 2f
        val arc = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(p.fg.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        rotate(turn) {
            drawArc(Brush.sweepGradient(Spectrum), 0f, 110f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
