package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.player.ConnectDeviceUi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.audio_output_playing
import com.music.bitchord.sharedui.resources.audio_pipeline
import com.music.bitchord.sharedui.resources.cast
import com.music.bitchord.sharedui.resources.cast_casting
import com.music.bitchord.sharedui.resources.cast_connecting
import com.music.bitchord.sharedui.resources.cast_row_subtitle
import com.music.bitchord.sharedui.resources.hdmi_output
import com.music.bitchord.sharedui.resources.listen_together_open
import com.music.bitchord.sharedui.resources.listen_together_open_count
import com.music.bitchord.sharedui.resources.personal_phone
import com.music.bitchord.sharedui.resources.this_phone
import com.music.bitchord.sharedui.resources.usb_audio
import com.music.bitchord.sharedui.resources.watch_output
import com.music.bitchord.sharedui.resources.wired_headphones
import com.music.bitchord.ui.player.AudioOutputDevice
import com.music.bitchord.ui.player.AudioOutputKind
import com.music.bitchord.ui.player.BluetoothForm
import com.music.bitchord.ui.player.PlayerPlatform
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The width of the audio output menu: more than a column of verbs, still a compact list. */
val AudioOutputMenuWidth = 300.dp

/**
 * What the glass menu off the player's output button holds, laid out as Apple Music's: the song at the top,
 * then pill rows — the party, the device playing now (which is also the volume: its pill fills with the level
 * and is dragged to change it), every other place the music could go, and the audio pipeline set apart at the
 * foot.
 *
 * Every device appears once. The one playing is named near the top and left out of the list below it. Auden
 * Connect lists the device it runs on, and every other install of the app on this phone (a second build is a
 * second device to it) — all of those are the phone's own output row, not further entries.
 *
 * Drawn inside an [IosMenuPopover]; what a row does beyond switching device — the party, the receiver picker,
 * the pipeline — goes to the host through [onParty], [onCast] and [onPipeline], which close the menu.
 */
@Composable
fun AudioOutputMenu(
    song: Song?,
    accountName: String?,
    onParty: () -> Unit,
    onCast: () -> Unit,
    onPipeline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = PlayerPlatform.host
    val colors = iosMenuColors()
    val outputs = host.rememberAudioOutputs()
    val cast by host.castState.collectAsStateWithLifecycle()
    val watch by host.watchOutput.collectAsStateWithLifecycle()
    val connect by host.connect.collectAsStateWithLifecycle()
    val party by host.party.collectAsStateWithLifecycle()
    val format by host.outputFormat.collectAsStateWithLifecycle()
    // A watch comes and goes; ask again each time the menu opens.
    LaunchedEffect(Unit) { host.refreshWatchOutput() }

    val connectDevices = if (connect.visible) connect.devices else emptyList()
    val thisDevice = connectDevices.firstOrNull { it.isThisDevice }
    // Other devices that are this phone: the same model and platform as this install, which is what a second
    // build of the app on it looks like from the room.
    val thisPhone: (ConnectDeviceUi) -> Boolean = { device ->
        device.isThisDevice || (thisDevice != null && device.platform == thisDevice.platform && device.name == thisDevice.name)
    }
    val remote = connectDevices.firstOrNull { it.isActive && !thisPhone(it) }

    val phoneTitle = accountName?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?.let { stringResource(Res.string.personal_phone, it) }
        ?: stringResource(Res.string.this_phone)
    val wired = stringResource(Res.string.wired_headphones)
    val usb = stringResource(Res.string.usb_audio)
    val hdmi = stringResource(Res.string.hdmi_output)
    val watchTitle = watch.name?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.watch_output)
    val castTitle = cast.connectedName ?: stringResource(Res.string.cast)
    val castSubtitle = stringResource(
        when {
            cast.connecting -> Res.string.cast_connecting
            cast.connectedName != null -> Res.string.cast_casting
            else -> Res.string.cast_row_subtitle
        },
    )

    val devices = buildList {
        outputs.forEach { device ->
            val title = when {
                device.name.isNotBlank() -> device.name
                device.kind == AudioOutputKind.WIRED -> wired
                device.kind == AudioOutputKind.USB -> usb
                device.kind == AudioOutputKind.HDMI -> hdmi
                else -> phoneTitle
            }
            // Playing somewhere else, this phone is where the music comes back to.
            val backHere = remote != null && thisDevice != null && device.kind == AudioOutputKind.PHONE
            add(
                OutputDestination(
                    key = "out-${device.id}",
                    title = title,
                    icon = outputIcon(device),
                    active = device.isActive && cast.connectedName == null && !watch.active && remote == null,
                    onClick = if (backHere) {
                        { host.connectSwitchTo(thisDevice!!.id) }
                    } else {
                        { host.selectAudioOutput(device.id) }
                    },
                ),
            )
        }
        if (watch.available || watch.active) {
            add(OutputDestination("watch", watchTitle, Icons.Rounded.Watch, watch.active, { host.selectWatchOutput() }))
        }
        if (cast.supported) {
            add(
                OutputDestination(
                    key = "cast",
                    title = castTitle,
                    icon = if (cast.connectedName != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                    active = cast.connectedName != null,
                    subtitle = castSubtitle,
                    // The receiver picker is a menu of its own: this row opens it, it is not itself a device.
                    onClick = onCast,
                    closes = false,
                ),
            )
        }
        connectDevices.filter { !thisPhone(it) }.forEach { device ->
            add(
                OutputDestination(
                    key = "connect-${device.id}",
                    title = device.name,
                    icon = connectIcon(device.platform),
                    active = device.isActive && cast.connectedName == null && !watch.active,
                    onClick = { host.connectSwitchTo(device.id) },
                ),
            )
        }
    }
    // The one playing, once. Casting, the watch and another Auden device each outrank this phone's outputs.
    val current = devices.firstOrNull { it.key == "cast" && it.active }
        ?: devices.firstOrNull { it.key == "watch" && it.active }
        ?: devices.firstOrNull { it.key.startsWith("connect-") && it.active }
        ?: devices.firstOrNull { it.key.startsWith("out-") && it.active }
        ?: devices.firstOrNull { it.key.startsWith("out-") }
    val others = devices.filter { it !== current }

    Column(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (song != null) SongPreview(song)
        PillRow(
            title = if (party.inParty) {
                stringResource(Res.string.listen_together_open_count, party.members.size)
            } else {
                stringResource(Res.string.listen_together_open)
            },
            icon = Icons.Rounded.Groups,
            onClick = onParty,
        )
        // Set a little apart from the devices under it.
        Spacer(Modifier.height(6.dp))
        if (current != null) {
            // This phone's own output is the volume: its pill fills with the level.
            PillRow(
                title = current.title,
                subtitle = current.subtitle,
                icon = current.icon,
                selected = true,
                volume = current.key.startsWith("out-"),
                routeKey = outputs,
                onClick = null,
            )
        }
        others.forEach { device ->
            PillRow(
                title = device.title,
                subtitle = device.subtitle,
                icon = device.icon,
                onClick = {
                    device.onClick()
                    if (device.closes) onDismiss()
                },
                chevron = device.key == "cast",
            )
        }
        // Set apart at the foot: not a place to send the music but a look at what it passes through.
        Spacer(Modifier.height(4.dp))
        PillRow(
            title = stringResource(Res.string.audio_pipeline),
            subtitle = format.summary.takeIf { it.isNotBlank() },
            icon = Icons.Rounded.GraphicEq,
            onClick = onPipeline,
            chevron = true,
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** The song at the top of the menu: its cover, title and artist — what the output is carrying. */
@Composable
private fun SongPreview(song: Song) {
    val colors = iosMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = rememberRemoteArtworkUrl(song)?.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.pressed),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = colors.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = colors.secondaryLabel,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One capsule of the menu: a glyph, a name, and what it is — a check when it is the one playing. With
 * [volume], the capsule is also the volume slider: filled with the level up to its width, and dragged to move it.
 */
@Composable
private fun PillRow(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    selected: Boolean = false,
    chevron: Boolean = false,
    volume: Boolean = false,
    routeKey: Any = Unit,
) {
    val colors = iosMenuColors()
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val system = PlayerPlatform.host.volume
    val systemLevel by system.level.collectAsStateWithLifecycle()
    var level by remember(system) { mutableFloatStateOf(systemLevel) }
    var dragging by remember { mutableStateOf(false) }
    if (volume) {
        LaunchedEffect(systemLevel) { if (!dragging) level = systemLevel }
        // Android swaps the volume index a moment after the device list changes, with no event of its own.
        LaunchedEffect(routeKey) {
            repeat(4) {
                if (!dragging) system.refresh()
                delay(250L)
            }
        }
    }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val setLevel: (Float) -> Unit = { x ->
        val next = (x / widthPx).coerceIn(0f, 1f)
        level = next
        system.set(next)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(
                when {
                    selected -> colors.label.copy(alpha = 0.14f)
                    pressed -> colors.pressed
                    else -> colors.label.copy(alpha = 0.07f)
                },
            )
            .then(
                if (volume) {
                    Modifier
                        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                        .drawBehind {
                            // The level, as a lighter fill from the left edge.
                            drawRect(colors.label.copy(alpha = 0.18f), size = Size(size.width * level, size.height))
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragging = true; setLevel(it.x) },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onHorizontalDrag = { change, _ -> setLevel(change.position.x) },
                            )
                        }
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.label,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = colors.secondaryLabel,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.label, modifier = Modifier.size(22.dp))
        } else if (chevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** One place the music can be, as the menu lists it. */
private class OutputDestination(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
    val subtitle: String? = null,
    /** Whether choosing it closes the menu; the receiver picker, which opens over it, does not need to. */
    val closes: Boolean = true,
)

private fun outputIcon(device: AudioOutputDevice): ImageVector = when (device.kind) {
    AudioOutputKind.PHONE -> Icons.Rounded.PhoneAndroid
    AudioOutputKind.WIRED -> Icons.Rounded.Headphones
    AudioOutputKind.USB -> Icons.Rounded.Usb
    AudioOutputKind.BLUETOOTH -> when (device.form) {
        BluetoothForm.BUDS -> Icons.Rounded.Earbuds
        BluetoothForm.HEADPHONES -> Icons.Rounded.Headphones
        BluetoothForm.SPEAKER -> Icons.Rounded.Speaker
        BluetoothForm.CAR -> Icons.Rounded.DirectionsCar
        BluetoothForm.UNKNOWN -> Icons.Rounded.Bluetooth
    }
    AudioOutputKind.HDMI -> Icons.Rounded.Tv
    AudioOutputKind.OTHER -> Icons.Rounded.Speaker
}

private fun connectIcon(platform: String): ImageVector = when (platform) {
    "android" -> Icons.Rounded.PhoneAndroid
    "watch" -> Icons.Rounded.Watch
    "tv" -> Icons.Rounded.Tv
    "web", "pc" -> Icons.Rounded.Computer
    else -> Icons.Rounded.Speaker
}
