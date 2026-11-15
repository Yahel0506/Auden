package com.music.bitchord.ui.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.ui.player.ThinSlider
import com.music.bitchord.ui.screens.SegmentedControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The playlist cover creator: a square to compose on — a background, texts, labels, stickers and pictures,
 * moved, turned and sized with the fingers — and the tabs that add and style them. Save hands back the cover
 * as a picture; Cancel throws it away.
 *
 * It starts from a playlist's name on a random gradient, so a cover is one tap from done and the rest is
 * taking it where one likes.
 */
@Composable
fun CoverCreator(
    title: String,
    songCount: Int,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    remember { CoverFonts.init(context) }
    val state = remember { CoverEditorState(title, songCount) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    BackHandler {
        when {
            state.editingTextId != null -> state.finishEditing(state.layers.firstOrNull { it.id == state.editingTextId }.let { (it as? TextLayer)?.text.orEmpty() })
            state.selectedId != null -> state.selectedId = null
            else -> onCancel()
        }
    }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                CreatorTopBar(
                    onCancel = onCancel,
                    saveEnabled = !saving,
                    onSave = {
                        saving = true
                        scope.launch {
                            val picture = withContext(Dispatchers.Default) {
                                CoverRenderer.render(SAVED_SIDE, state.background, state.layers.toList())
                            }
                            onSave(picture)
                        }
                    },
                )
                // Outside the square, a tap puts the selection away.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.selectedId = null },
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight)
                        CoverCanvas(state, side)
                    }
                }
                if (state.panelOpen) {
                    CoverPanel(state, Modifier.fillMaxWidth())
                }
                CreatorTabBar(state)
            }
            state.editingTextId?.let { id ->
                val layer = state.layers.firstOrNull { it.id == id } as? TextLayer
                if (layer != null) TextEntry(layer.text, onDone = state::finishEditing)
            }
        }
    }
}

private const val SAVED_SIDE = 1200

@Composable
private fun CreatorTopBar(onCancel: () -> Unit, saveEnabled: Boolean, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground) }
        Text(
            text = stringResource(R.string.cover_creator_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave, enabled = saveEnabled) {
            Text(stringResource(R.string.save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ---- The square ----

@Composable
private fun CoverCanvas(state: CoverEditorState, side: Dp) {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val accent = MaterialTheme.colorScheme.primary
    val sidePx = with(density) { side.toPx() }
    Box(Modifier.size(side)) {
        Canvas(
            Modifier
                .size(side)
                .shadow(14.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(sidePx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A tap on the square is the square's: left unconsumed it reached the area behind it,
                        // which puts the selection away the moment it was made.
                        down.consume()
                        val hit = state.layers.lastOrNull { CoverRenderer.hits(it, sidePx, down.position.x, down.position.y) }
                        val alreadySelected = hit != null && hit.id == state.selectedId
                        if (hit != null) state.select(hit.id)
                        // Fingers that miss every layer still move the one that is selected.
                        val targetId = hit?.id ?: state.selectedId
                        var travelled = 0f
                        var transformed = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val zoom = event.calculateZoom()
                            val turn = event.calculateRotation()
                            val pan = event.calculatePan()
                            travelled += pan.getDistance()
                            val layer = state.layers.firstOrNull { it.id == targetId }
                            if (layer != null && (travelled > slop || zoom != 1f || turn != 0f)) {
                                transformed = true
                                state.replace(
                                    layer.placed(
                                        cx = layer.cx + pan.x / sidePx,
                                        cy = layer.cy + pan.y / sidePx,
                                        scale = (layer.scale * zoom).coerceIn(0.15f, 8f),
                                        rotation = layer.rotation + turn,
                                    ),
                                )
                            }
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (!transformed) {
                            if (hit == null) {
                                state.selectedId = null
                            } else if (alreadySelected && hit is TextLayer) {
                                // Tapping a text that is already selected types into it.
                                state.editingTextId = hit.id
                            }
                        }
                    }
                },
        ) {
            drawIntoCanvas { CoverRenderer.draw(it.nativeCanvas, size.width, state.background, state.layers) }
            state.selected?.let { layer ->
                val (w, h) = CoverRenderer.sizeOf(layer, size.width)
                val sw = w * layer.scale
                val sh = h * layer.scale
                rotate(layer.rotation, pivot = Offset(layer.cx * size.width, layer.cy * size.width)) {
                    val topLeft = Offset(layer.cx * size.width - sw / 2f, layer.cy * size.width - sh / 2f)
                    drawRoundRect(accent, topLeft, Size(sw, sh), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    val r = 4.5.dp.toPx()
                    for (dx in floatArrayOf(0f, sw)) for (dy in floatArrayOf(0f, sh)) {
                        drawCircle(Color.White, r, topLeft + Offset(dx, dy))
                        drawCircle(accent, r, topLeft + Offset(dx, dy), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        state.selected?.let { layer -> SelectionToolbar(state, layer, sidePx, side) }
    }
}

/** The pill over the selected layer: edit, forward, backward, delete. */
@Composable
private fun SelectionToolbar(state: CoverEditorState, layer: CoverLayer, sidePx: Float, side: Dp) {
    val density = LocalDensity.current
    val (w, h) = CoverRenderer.sizeOf(layer, sidePx)
    val r = Math.toRadians(layer.rotation.toDouble())
    val halfHeight = (abs(w * sin(r)) + abs(h * cos(r))).toFloat() * layer.scale / 2f
    val bar = 48.dp
    val barHeightPx = with(density) { bar.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerY = layer.cy * sidePx
    val above = centerY - halfHeight - barHeightPx - gap
    val y = if (above >= 0f) above else (centerY + halfHeight + gap).coerceAtMost(sidePx - barHeightPx)
    val buttons = if (layer is TextLayer) 4 else 3
    val barWidth = 48.dp * buttons + 8.dp
    val x = (with(density) { (layer.cx * sidePx).toDp() } - barWidth / 2).coerceIn(0.dp, side - barWidth)
    Row(
        Modifier
            .offset(x = x, y = with(density) { y.toDp() })
            .height(bar)
            .shadow(10.dp, CircleShape)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer is TextLayer) {
            ToolbarButton(Icons.Rounded.TextFields, stringResource(R.string.cover_edit_text)) { state.editingTextId = layer.id }
        }
        ToolbarButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.cover_bring_forward), enabled = state.layers.lastOrNull()?.id != layer.id) {
            state.bringForward(layer.id)
        }
        ToolbarButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.cover_send_backward), enabled = state.layers.firstOrNull()?.id != layer.id) {
            state.sendBackward(layer.id)
        }
        ToolbarButton(Icons.Rounded.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) { state.delete(layer.id) }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, description: String, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, description, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
    }
}

/** Typing into a text: the square dimmed, the words large in the middle of the screen. */
@Composable
private fun TextEntry(initial: String, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { onDone(value) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone(value) }
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = MaterialTheme.typography.displaySmall.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.cover_type_here),
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

// ---- Tabs ----

@Composable
private fun CreatorTabBar(state: CoverEditorState) {
    val tabs = listOf(
        Triple(CoverTab.BACKGROUND, Icons.Rounded.Gradient, R.string.cover_tab_background),
        Triple(CoverTab.TEXT, Icons.Rounded.TextFields, R.string.cover_tab_text),
        Triple(CoverTab.IMAGE, Icons.Rounded.Image, R.string.cover_tab_image),
        Triple(CoverTab.LABELS, Icons.Rounded.LocalOffer, R.string.cover_tab_labels),
        Triple(CoverTab.STICKERS, Icons.Rounded.EmojiEmotions, R.string.cover_tab_stickers),
    )
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)) {
        tabs.forEach { (tab, icon, label) ->
            val on = state.tab == tab && state.panelOpen
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (state.tab == tab) state.panelOpen = !state.panelOpen else { state.tab = tab; state.panelOpen = true }
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                Text(stringResource(label), fontSize = 11.sp, color = tint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CoverPanel(state: CoverEditorState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .height(PANEL_HEIGHT)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The grabber every sheet has; dragging it away is a tap on the tab here.
        Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
        Spacer(Modifier.height(10.dp))
        when (state.tab) {
            CoverTab.BACKGROUND -> BackgroundPanel(state)
            CoverTab.TEXT -> TextPanel(state)
            CoverTab.IMAGE -> ImagePanel(state)
            CoverTab.LABELS -> LabelPanel(state)
            CoverTab.STICKERS -> StickerPanel(state)
        }
    }
}

private val PANEL_HEIGHT = 340.dp

// ---- Shared pieces ----

@Composable
private fun PillButton(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EmptyPanel(button: String, hint: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PillButton(button, Icons.Rounded.AddCircleOutline, onClick)
        Spacer(Modifier.height(14.dp))
        Text(hint, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    val foreground = MaterialTheme.colorScheme.onSurface
    // The slider keeps the callback it first saw for as long as a drag lasts, and a callback made for one effect
    // would go on editing that effect after another was chosen: it is always handed on to the latest.
    val latest by rememberUpdatedState(onChange)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = foreground, modifier = Modifier.width(92.dp), maxLines = 1)
        ThinSlider(
            value = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
            onValueChange = { latest(range.start + it * (range.endInclusive - range.start)) },
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            activeColor = foreground.copy(alpha = 0.92f),
            inactiveColor = foreground.copy(alpha = 0.26f),
            modifier = Modifier.weight(1f),
        )
        Text(display, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
}

/**
 * The colour of whatever is being coloured, in one place: twelve slots — the hues, the neutral ramp and, last, a
 * rainbow one that opens any colour at all (a saturation/brightness square, a hue bar and a hex code) where the
 * light/dark slider was. A ring marks the slot in use, so which of the two is working is never a question.
 * [key] is what is being coloured; a new one starts the custom controls from its colour.
 */
@Composable
private fun ColorPicker(current: Int, key: Any?, onPick: (Int) -> Unit) {
    val latestPick by rememberUpdatedState(onPick)
    val mono = CoverPalette.hues.size
    val custom = mono + 1
    var swatch by remember(key) { mutableIntStateOf(mono) }
    var level by remember(key) { mutableFloatStateOf(0.5f) }
    fun color(s: Int, t: Float) = if (s >= mono) CoverPalette.gray(t) else CoverPalette.shade(CoverPalette.hues[s], t)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (column in 0..5) {
                    val index = row * 6 + column
                    if (index > custom) { Spacer(Modifier.size(34.dp)); continue }
                    val ring = if (index == swatch) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .then(
                                when {
                                    index == custom -> Modifier.background(androidx.compose.ui.graphics.Brush.sweepGradient(List(7) { Color.hsv(it * 60f % 360f, 0.9f, 1f) }))
                                    index == mono -> Modifier.background(Color.Gray)
                                    else -> Modifier.background(Color(CoverPalette.hues[index]))
                                },
                            )
                            .then(ring)
                            .clickable {
                                swatch = index
                                if (index != custom) {
                                    level = 0.5f
                                    latestPick(color(index, 0.5f))
                                }
                            },
                    ) {
                        when (index) {
                            mono -> Canvas(Modifier.fillMaxSize()) {
                                drawArc(Color.White, -90f, 180f, true)
                                drawArc(Color.Black, 90f, 180f, true)
                            }
                            // The colour in use, in the middle of the rainbow, so it reads as "this one, adjusted".
                            custom -> if (swatch == custom) Box(Modifier.align(Alignment.Center).size(14.dp).clip(CircleShape).background(Color(current)).border(1.5.dp, Color.White, CircleShape))
                        }
                    }
                }
            }
        }
        if (swatch == custom) {
            CustomColor(current, key) { latestPick(it) }
        } else {
            ThinSlider(
                value = level,
                onValueChange = { level = it; latestPick(color(swatch, it)) },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                activeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.26f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CustomColor(current: Int, key: Any?, onPick: (Int) -> Unit) {
    val start = remember(key) { FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) } }
    var hue by remember(key) { mutableFloatStateOf(start[0]) }
    var sat by remember(key) { mutableFloatStateOf(start[1]) }
    var value by remember(key) { mutableFloatStateOf(start[2]) }
    var hex by remember(key) { mutableStateOf("%06X".format(current and 0xFFFFFF)) }
    fun emit() {
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        hex = "%06X".format(c and 0xFFFFFF)
        onPick(c)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(118.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(key) {
                    awaitEachGesture {
                        var change = awaitFirstDown()
                        do {
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            emit()
                            change.consume()
                            val event = awaitPointerEvent()
                            change = event.changes.first()
                        } while (change.pressed)
                    }
                },
        ) {
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val at = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), at, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), 10.5.dp.toPx(), at, style = Stroke(1.dp.toPx()))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(CircleShape)
                .pointerInput(key) {
                    awaitEachGesture {
                        var change = awaitFirstDown()
                        do {
                            hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            emit()
                            change.consume()
                            val event = awaitPointerEvent()
                            change = event.changes.first()
                        } while (change.pressed)
                    }
                },
        ) {
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(List(7) { Color.hsv(it * 60f % 360f, 1f, 1f) }))
            val x = hue / 360f * size.width
            drawCircle(Color.White, size.height / 2f - 2.dp.toPx(), Offset(x.coerceIn(size.height / 2f, size.width - size.height / 2f), size.height / 2f), style = Stroke(3.dp.toPx()))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape))
            Text("#", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = hex,
                onValueChange = { typed ->
                    val clean = typed.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    hex = clean
                    if (clean.length == 6) {
                        val c = (0xFF shl 24) or clean.toInt(16)
                        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        onPick(c)
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .width(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Choices as tiles, in rows of [columns] that scroll down: each drawn by [preview] — the effect, font or style
 * itself, shown rather than named — with its name under it, and the chosen one outlined.
 */
@Composable
private fun <T> PreviewGrid(
    items: List<T>,
    columns: Int,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    preview: androidx.compose.ui.graphics.drawscope.DrawScope.(T) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    val on = selected(item)
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.weight(1f).clip(shape).clickable { onClick(item) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.25f)
                                .clip(shape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
                        ) { preview(item) }
                        Text(
                            label(item),
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** [bitmap] fitted into the tile, at [fill] of its size. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.fitBitmap(bitmap: Bitmap, fill: Float = 0.82f) {
    val k = minOf(size.width / bitmap.width, size.height / bitmap.height) * fill
    val w = bitmap.width * k
    val h = bitmap.height * k
    drawIntoCanvas {
        it.nativeCanvas.drawBitmap(
            bitmap, null,
            android.graphics.RectF((size.width - w) / 2f, (size.height - h) / 2f, (size.width + w) / 2f, (size.height + h) / 2f),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG),
        )
    }
}

// ---- Background ----

@Composable
private fun BackgroundPanel(state: CoverEditorState) {
    var page by remember { mutableIntStateOf(0) }
    var stop by remember { mutableIntStateOf(0) }
    val bg = state.background
    SegmentedControl(
        options = listOf(stringResource(R.string.cover_color), stringResource(R.string.cover_style)),
        selectedIndex = page,
        onSelect = { page = it },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(10.dp))
    if (page == 0) {
        val selectedStop = stop.coerceIn(0, bg.colors.lastIndex)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                bg.colors.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .then(if (index == selectedStop) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { stop = index },
                    )
                }
                if (bg.colors.size > 2) {
                    IconButton(onClick = { state.background = bg.copy(colors = bg.colors.toMutableList().also { it.removeAt(selectedStop) }); stop = 0 }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.cover_remove_color), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (bg.colors.size < 4) {
                    IconButton(onClick = { state.background = bg.copy(colors = bg.colors + CoverPalette.random(Random)); stop = bg.colors.size }) {
                        Icon(Icons.Rounded.AddCircleOutline, stringResource(R.string.cover_add_color), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { state.background = bg.copy(colors = bg.colors.map { CoverPalette.random(Random) }) }) {
                    Icon(Icons.Rounded.Shuffle, stringResource(R.string.cover_shuffle_colors), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            ColorPicker(bg.colors[selectedStop], key = selectedStop) { picked ->
                val now = state.background
                val i = selectedStop.coerceIn(0, now.colors.lastIndex)
                state.background = now.copy(colors = now.colors.toMutableList().also { it[i] = picked })
            }
            Spacer(Modifier.height(12.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LabeledSlider(stringResource(R.string.cover_grain), bg.grain, 0f..1f, (bg.grain * 100).roundToInt().toString()) {
                        state.background = state.background.copy(grain = it)
                    }
                }
                IconButton(onClick = { state.background = bg.copy(seed = Random.nextInt(10_000)) }) {
                    Icon(Icons.Rounded.Shuffle, stringResource(R.string.cover_reroll), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            PreviewGrid(
                items = BackgroundStyle.entries.toList(),
                columns = 3,
                selected = { it == bg.style },
                label = { stringResource(it.nameRes()) },
                onClick = { state.background = bg.copy(style = it) },
            ) { style ->
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    n.save()
                    n.clipRect(0f, 0f, size.width, size.height)
                    val side = maxOf(size.width, size.height)
                    n.translate((size.width - side) / 2f, (size.height - side) / 2f)
                    CoverRenderer.draw(n, side, bg.copy(style = style, grain = 0f), emptyList())
                    n.restore()
                }
            }
        }
    }
}

private fun BackgroundStyle.nameRes(): Int = when (this) {
    BackgroundStyle.HORIZONTAL -> R.string.cover_bg_horizontal
    BackgroundStyle.VERTICAL -> R.string.cover_bg_vertical
    BackgroundStyle.RADIAL -> R.string.cover_bg_radial
    BackgroundStyle.CORNERS -> R.string.cover_bg_corners
    BackgroundStyle.SOLID -> R.string.cover_bg_solid
    BackgroundStyle.MESH -> R.string.cover_bg_mesh
    BackgroundStyle.AURORA -> R.string.cover_bg_aurora
    BackgroundStyle.SUNBURST -> R.string.cover_bg_sunburst
    BackgroundStyle.STRIPES -> R.string.cover_bg_stripes
    BackgroundStyle.WAVES -> R.string.cover_bg_waves
    BackgroundStyle.DOTS -> R.string.cover_bg_dots
    BackgroundStyle.RIPPLE -> R.string.cover_bg_ripple
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: (T) -> Boolean, icon: (T) -> ImageVector?, label: @Composable (T) -> String, onClick: (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items) { item ->
            val on = selected(item)
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    .clickable { onClick(item) }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .width(64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tint = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                icon(item)?.let { Icon(it, null, tint = tint, modifier = Modifier.size(24.dp)) }
                Text(label(item), fontSize = 13.sp, color = tint, maxLines = 1)
            }
        }
    }
}

/** Pills to pick one of a few named things: the sticker looks. */
@Composable
private fun <T> PillRow(items: List<T>, selected: T, label: @Composable (T) -> String, onClick: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            val on = item == selected
            Text(
                label(item),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    .clickable { onClick(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ---- Text ----

@Composable
private fun TextPanel(state: CoverEditorState) {
    val layer = state.selected as? TextLayer
    if (layer == null) {
        EmptyPanel(stringResource(R.string.cover_add_text), stringResource(R.string.cover_add_text_hint)) { state.addText() }
        return
    }
    var page by remember(layer.id) { mutableIntStateOf(1) }
    SegmentedControl(
        options = listOf(R.string.cover_font, R.string.cover_style, R.string.cover_color, R.string.cover_effects).map { stringResource(it) },
        selectedIndex = page,
        onSelect = { page = it },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    val ink = MaterialTheme.colorScheme.onSurface
    when (page) {
        0 -> PreviewGrid(
            items = CoverFont.entries.toList(),
            columns = 3,
            selected = { it == layer.font },
            label = { stringResource(it.nameRes()) },
            onClick = { state.replace(layer.copy(font = it)) },
        ) { font ->
            drawIntoCanvas {
                val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = CoverFonts.typeface(font, 700)
                    textSize = size.height * 0.46f
                    color = ink.toArgb()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                it.nativeCanvas.drawText("Aa", size.width / 2f, size.height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
        1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LabeledSlider(stringResource(R.string.cover_size), layer.size, 30f..220f, layer.size.roundToInt().toString()) { state.replace(layer.copy(size = it)) }
            LabeledSlider(stringResource(R.string.cover_width), layer.stretch, 0.5f..3f, "${(layer.stretch * 100).roundToInt()}%") { state.replace(layer.copy(stretch = it)) }
            LabeledSlider(stringResource(R.string.cover_weight), layer.weight.toFloat(), 100f..900f, layer.weight.toString()) { state.replace(layer.copy(weight = (it / 100f).roundToInt() * 100)) }
            LabeledSlider(stringResource(R.string.cover_slant), layer.slant, 0f..1f, (layer.slant * 100).roundToInt().toString()) { state.replace(layer.copy(slant = it)) }
            LabeledSlider(stringResource(R.string.cover_letter_spacing), layer.spacing, -0.1f..0.6f, (layer.spacing * 100).roundToInt().toString()) { state.replace(layer.copy(spacing = it)) }
            LabeledSlider(stringResource(R.string.cover_line_height), layer.leading, 0.6f..1.6f, (layer.leading * 100).roundToInt().toString()) { state.replace(layer.copy(leading = it)) }
            LabeledSlider(stringResource(R.string.cover_opacity), layer.opacity, 0.1f..1f, (layer.opacity * 100).roundToInt().toString()) { state.replace(layer.copy(opacity = it)) }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                ChoiceChip(stringResource(R.string.cover_caps), layer.caps, null) { state.replace(layer.copy(caps = !layer.caps)) }
                PillButton(stringResource(R.string.cover_randomize), Icons.Rounded.Shuffle) { state.replace(layer.randomized()) }
            }
        }
        2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            var stroke by remember(layer.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip(stringResource(R.string.cover_fill), !stroke, Color(layer.fill)) { stroke = false }
                ChoiceChip(stringResource(R.string.cover_stroke), stroke, layer.stroke?.let { Color(it) }) {
                    stroke = true
                    if (layer.stroke == null) state.replace(layer.copy(stroke = 0xFF000000.toInt()))
                }
                Spacer(Modifier.weight(1f))
                if (stroke && layer.stroke != null) {
                    IconButton(onClick = { state.replace(layer.copy(stroke = null)); stroke = false }) {
                        Icon(Icons.Rounded.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ColorPicker(if (stroke) layer.stroke ?: 0 else layer.fill, key = layer.id to stroke) { picked ->
                val now = state.selected as? TextLayer ?: return@ColorPicker
                state.replace(if (stroke) now.copy(stroke = picked) else now.copy(fill = picked))
            }
            Spacer(Modifier.height(12.dp))
        }
        else -> Column(Modifier.fillMaxSize()) {
            val params = layer.effect.params()
            params.getOrNull(0)?.let { (res, range) ->
                LabeledSlider(stringResource(res), layer.effectA, range, layer.effectA.roundToInt().toString()) { state.replace(layer.copy(effectA = it)) }
            }
            params.getOrNull(1)?.let { (res, range) ->
                LabeledSlider(stringResource(res), layer.effectB, range, layer.effectB.roundToInt().toString()) { state.replace(layer.copy(effectB = it)) }
            }
            if (params.isNotEmpty()) Spacer(Modifier.height(6.dp))
            val previews = remember(ink) {
                TextEffect.entries.associateWith { effect ->
                    val (a, b) = effect.defaults()
                    CoverRenderer.textPreview(TextLayer(0, "Aa", weight = 800, fill = ink.toArgb(), effect = effect, effectA = a, effectB = b), 320f)
                }
            }
            PreviewGrid(
                items = TextEffect.entries.toList(),
                columns = 3,
                selected = { it == layer.effect },
                label = { stringResource(it.nameRes()) },
                onClick = {
                    val (a, b) = it.defaults()
                    state.replace(layer.copy(effect = it, effectA = a, effectB = b))
                },
            ) { effect -> previews[effect]?.let { fitBitmap(it) } }
        }
    }
}

private fun CoverFont.nameRes(): Int = when (this) {
    CoverFont.PRO -> R.string.cover_font_pro
    CoverFont.SANS -> R.string.cover_font_sans
    CoverFont.CONDENSED -> R.string.cover_font_condensed
    CoverFont.SERIF -> R.string.cover_font_serif
    CoverFont.MONO -> R.string.cover_font_mono
    CoverFont.TYPEWRITER -> R.string.cover_font_typewriter
    CoverFont.SCRIPT -> R.string.cover_font_script
    CoverFont.HANDWRITTEN -> R.string.cover_font_handwritten
    CoverFont.SMALLCAPS -> R.string.cover_font_smallcaps
}

private fun TextEffect.nameRes(): Int = when (this) {
    TextEffect.NONE -> R.string.cover_effect_none
    TextEffect.SHADOW -> R.string.cover_effect_shadow
    TextEffect.NEON -> R.string.cover_effect_neon
    TextEffect.GLITCH -> R.string.cover_effect_glitch
    TextEffect.EXTRUDE -> R.string.cover_effect_extrude
    TextEffect.ECHO -> R.string.cover_effect_echo
    TextEffect.ARC -> R.string.cover_effect_arc
    TextEffect.WAVE -> R.string.cover_effect_wave
    TextEffect.DISPLACE -> R.string.cover_effect_displace
    TextEffect.STACK -> R.string.cover_effect_stack
    TextEffect.FLAG -> R.string.cover_effect_flag
    TextEffect.SPHERE -> R.string.cover_effect_sphere
}

/** The two things an effect can be set by: what each is called and how far it goes. */
private fun TextEffect.params(): List<Pair<Int, ClosedFloatingPointRange<Float>>> = when (this) {
    TextEffect.NONE -> emptyList()
    TextEffect.SHADOW -> listOf(R.string.cover_param_distance to 0f..40f, R.string.cover_param_blur to 1f..40f)
    TextEffect.NEON -> listOf(R.string.cover_param_glow to 2f..24f, R.string.cover_param_intensity to 1f..10f)
    TextEffect.GLITCH -> listOf(R.string.cover_param_split to 1f..20f, R.string.cover_param_cuts to 0f..10f)
    TextEffect.EXTRUDE -> listOf(R.string.cover_param_depth to 2f..40f, R.string.cover_param_angle to 0f..360f)
    TextEffect.ECHO -> listOf(R.string.cover_param_copies to 1f..8f, R.string.cover_param_distance to 2f..40f)
    TextEffect.ARC -> listOf(R.string.cover_param_bend to -10f..10f, R.string.cover_param_taper to 0f..10f)
    TextEffect.WAVE -> listOf(R.string.cover_param_amplitude to 1f..20f, R.string.cover_param_frequency to 1f..10f)
    TextEffect.DISPLACE -> listOf(R.string.cover_param_frequency to 1f..8f, R.string.cover_param_intensity to 1f..30f)
    TextEffect.STACK, TextEffect.FLAG -> listOf(R.string.cover_param_copies to 1f..8f, R.string.cover_param_spacing to 2f..15f)
    TextEffect.SPHERE -> listOf(R.string.cover_param_intensity to 1f..10f)
}

@Composable
private fun ChoiceChip(text: String, on: Boolean, color: Color?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Box(Modifier.size(26.dp).clip(CircleShape).background(color ?: Color.Transparent).border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape))
    }
}

// ---- Image ----

@Composable
private fun ImagePanel(state: CoverEditorState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { decodeScaled(context, uri, 1600) }.getOrNull() }?.let(state::addImage)
        }
    }
    val add = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val layer = state.selected as? ImageLayer
    if (layer == null) {
        EmptyPanel(stringResource(R.string.cover_add_image), stringResource(R.string.cover_add_image_hint), add)
        return
    }
    var page by remember(layer.id) { mutableIntStateOf(0) }
    SegmentedControl(
        options = listOf(R.string.cover_effects, R.string.cover_shape, R.string.cover_tab_image).map { stringResource(it) },
        selectedIndex = page,
        onSelect = { page = it },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    when (page) {
        0 -> Column(Modifier.fillMaxSize()) {
            // One filter per picture: choosing another replaces it, choosing the same one again takes it off.
            val active = layer.effects.entries.firstOrNull()
            if (active != null) {
                LabeledSlider(stringResource(active.key.nameRes()), active.value, 0f..1f, (active.value * 100).roundToInt().toString()) {
                    state.replace(layer.with(effects = mapOf(active.key to it)))
                }
                Spacer(Modifier.height(6.dp))
            }
            val thumb = remember(layer.bitmap) {
                val k = 220f / maxOf(layer.bitmap.width, layer.bitmap.height)
                Bitmap.createScaledBitmap(layer.bitmap, (layer.bitmap.width * k).roundToInt().coerceAtLeast(1), (layer.bitmap.height * k).roundToInt().coerceAtLeast(1), true)
            }
            val previews = remember(thumb) {
                ImageEffect.entries.associateWith { CoverImageFx.render(ImageLayer(-1, thumb, mapOf(it to 0.7f)), useCache = false) }
            }
            PreviewGrid(
                items = ImageEffect.entries.toList(),
                columns = 3,
                selected = { it in layer.effects },
                label = { stringResource(it.nameRes()) },
                onClick = { effect ->
                    state.replace(layer.with(effects = if (effect in layer.effects) emptyMap() else mapOf(effect to 0.6f)))
                },
            ) { effect -> previews[effect]?.let { fitBitmap(it, 1f) } }
        }
        1 -> ChipRow(
            items = ImageShape.entries.toList(),
            selected = { it == layer.shape },
            icon = {
                when (it) {
                    ImageShape.RECTANGLE -> Icons.Rounded.CropSquare
                    ImageShape.ROUNDED -> Icons.Rounded.CropSquare
                    ImageShape.CIRCLE -> Icons.Rounded.CenterFocusStrong
                }
            },
            label = {
                stringResource(
                    when (it) {
                        ImageShape.RECTANGLE -> R.string.cover_shape_square
                        ImageShape.ROUNDED -> R.string.cover_shape_rounded
                        ImageShape.CIRCLE -> R.string.cover_shape_circle
                    },
                )
            },
            onClick = { state.replace(layer.with(shape = it)) },
        )
        else -> Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            PillButton(stringResource(R.string.cover_add_image), Icons.Rounded.AddCircleOutline, add)
        }
    }
}

private fun ImageEffect.nameRes(): Int = when (this) {
    ImageEffect.GRAYSCALE -> R.string.cover_fx_mono
    ImageEffect.SEPIA -> R.string.cover_fx_sepia
    ImageEffect.DUOTONE -> R.string.cover_fx_duotone
    ImageEffect.INVERT -> R.string.cover_fx_invert
    ImageEffect.POSTERIZE -> R.string.cover_fx_posterize
    ImageEffect.BLUR -> R.string.cover_param_blur
    ImageEffect.PIXELATE -> R.string.cover_fx_pixelate
    ImageEffect.HALFTONE -> R.string.cover_fx_halftone
    ImageEffect.GLITCH -> R.string.cover_effect_glitch
    ImageEffect.GLOW -> R.string.cover_param_glow
    ImageEffect.VIGNETTE -> R.string.cover_fx_vignette
}

private fun decodeScaled(context: Context, uri: Uri, longest: Int): Bitmap? {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > longest) {
                val k = longest.toFloat() / big
                decoder.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > longest) sample *= 2
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

// ---- Labels and stickers ----

@Composable
private fun LabelPanel(state: CoverEditorState) {
    val selectedLabel = state.selected as? LabelLayer
    TileGrid(
        columns = 2,
        tileRatio = 1.9f,
        items = LabelStyle.entries.toList(),
        selected = { it == selectedLabel?.style },
        onClick = { style -> if (selectedLabel != null) state.replace(selectedLabel.copy(style = style)) else state.addLabel(style) },
    ) { style, canvas, w, h ->
        // Set at 1.5 tile widths, so a long title is cut about the way it will be on the cover.
        canvas.translate(w / 2f, h / 2f)
        CoverRenderer.drawLayer(canvas, w * 1.5f, LabelLayer(0, style, state.title.ifBlank { " " }, state.countText, cx = 0f, cy = 0f, scale = 0.6f))
    }
}

@Composable
private fun StickerPanel(state: CoverEditorState) {
    var group by remember { mutableStateOf(StickerGroup.AUDEN) }
    Column(Modifier.fillMaxSize()) {
        PillRow(
            items = StickerGroup.entries.toList(),
            selected = group,
            label = {
                stringResource(
                    when (it) {
                        StickerGroup.AUDEN -> R.string.cover_stickers_auden
                        StickerGroup.VINTAGE -> R.string.cover_stickers_vintage
                        StickerGroup.FUTURE -> R.string.cover_stickers_future
                        StickerGroup.Y2K -> R.string.cover_stickers_y2k
                        StickerGroup.GRAPHIC -> R.string.cover_stickers_graphic
                    },
                )
            },
            onClick = { group = it },
        )
        Spacer(Modifier.height(10.dp))
        TileGrid(
            columns = 4,
            tileRatio = 1f,
            items = StickerKind.entries.filter { it.group == group },
            selected = { false },
            onClick = { state.addSticker(it) },
        ) { kind, canvas, w, h ->
            canvas.translate(w / 2f, h / 2f)
            CoverRenderer.drawLayer(canvas, w, StickerLayer(0, kind, cx = 0f, cy = 0f, scale = 2.4f))
        }
    }
}

/** Tiles in rows of [columns], each drawn by [draw] on a canvas of the tile's size; the chosen one is outlined. */
@Composable
private fun <T> TileGrid(
    columns: Int,
    tileRatio: Float,
    items: List<T>,
    selected: (T) -> Boolean,
    onClick: (T) -> Unit,
    draw: (T, android.graphics.Canvas, Float, Float) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    val shape = RoundedCornerShape(12.dp)
                    Canvas(
                        Modifier
                            .weight(1f)
                            .aspectRatio(tileRatio)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .then(if (selected(item)) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                            .clickable { onClick(item) },
                    ) {
                        drawIntoCanvas { c -> c.nativeCanvas.save(); draw(item, c.nativeCanvas, size.width, size.height); c.nativeCanvas.restore() }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
