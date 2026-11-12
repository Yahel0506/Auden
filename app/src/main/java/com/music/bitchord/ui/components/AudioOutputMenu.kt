package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import com.music.bitchord.ui.player.ThinSlider
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The width of the audio output menu: more than a column of verbs, still a compact list. */
val AudioOutputMenuWidth = 300.dp

/**
 * What the glass menu off the player's output button holds: the party, the device the music is on now, the
 * others it could be on — this phone's outputs, a watch, a receiver, the account's other Auden devices, all
 * in one list — the volume, and, set apart at the foot, the audio pipeline.
 *
 * Every device appears once. The one playing is named at the top and left out of the list below it, and the
 * account's own copy of this phone (Auden Connect lists the device it is running on) is the phone's output
 * row, not a second one.
 *
 * Drawn inside an [IosMenuPopover]; what a row does beyond switching device — the party, the receiver picker,
 * the pipeline — goes to the host through [onParty], [onCast] and [onPipeline], which close the menu.
 */
@Composable
fun AudioOutputMenu(
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

    // The account's other Auden devices. Its entry for this very device is the phone's own output row below.
    val connectDevices = if (connect.visible) connect.devices else emptyList()
    val thisDevice = connectDevices.firstOrNull { it.isThisDevice }
    val remote = connectDevices.firstOrNull { it.isActive && !it.isThisDevice }

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
        connectDevices.filter { !it.isThisDevice }.forEach { device ->
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
    val playing = stringResource(Res.string.audio_output_playing)

    Column {
        // The party, first: the way into listening with other people.
        IosMenuRow(
            label = if (party.inParty) {
                stringResource(Res.string.listen_together_open_count, party.members.size)
            } else {
                stringResource(Res.string.listen_together_open)
            },
            icon = Icons.Rounded.Groups,
            onClick = onParty,
        )
        if (current != null) {
            IosMenuSeparator(colors)
            IosMenuRow(
                label = current.title,
                description = current.subtitle ?: playing,
                icon = current.icon,
                onClick = null,
                trailing = { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.label, modifier = Modifier.size(20.dp)) },
            )
        }
        if (others.isNotEmpty()) {
            IosMenuSeparator(colors)
            others.forEach { device ->
                IosMenuRow(
                    label = device.title,
                    description = device.subtitle,
                    icon = device.icon,
                    onClick = {
                        device.onClick()
                        if (device.closes) onDismiss()
                    },
                    trailing = if (device.key == "cast") {
                        { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondaryLabel, modifier = Modifier.size(20.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
        IosMenuSeparator(colors)
        MenuVolume(routeKey = outputs)
        // Set apart at the foot: not a place to send the music but a look at what it passes through.
        IosMenuSeparator(colors)
        IosMenuRow(
            label = stringResource(Res.string.audio_pipeline),
            description = format.summary.takeIf { it.isNotBlank() },
            icon = Icons.Rounded.GraphicEq,
            onClick = onPipeline,
            trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.secondaryLabel, modifier = Modifier.size(20.dp)) },
        )
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

/** The phone's media volume on the player's own slider, re-read when the route changes. */
@Composable
private fun MenuVolume(routeKey: Any) {
    val system = PlayerPlatform.host.volume
    val systemLevel by system.level.collectAsStateWithLifecycle()
    var level by remember(system) { mutableFloatStateOf(systemLevel) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(systemLevel) { if (!dragging) level = systemLevel }
    // Android swaps the volume index a moment after the device list changes, with no event of its own.
    LaunchedEffect(routeKey) {
        repeat(4) {
            if (!dragging) system.refresh()
            delay(250L)
        }
    }
    val colors = iosMenuColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = if (level > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            contentDescription = null,
            tint = colors.secondaryLabel,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        ThinSlider(
            value = level,
            onValueChange = {
                dragging = true
                level = it
                system.set(it)
            },
            onValueChangeFinished = { dragging = false },
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

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
