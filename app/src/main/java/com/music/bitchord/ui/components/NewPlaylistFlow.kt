package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_mono
import com.music.bitchord.sharedui.resources.ytmusic_mono
import com.music.bitchord.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import org.jetbrains.compose.resources.vectorResource

/** Where a playlist ends up: kept on this device, or saved to the YouTube Music account. */
enum class PlaylistDestination { DEVICE, YT_MUSIC }

private enum class FlowStep { ROOT, NEW, IMPORT_SOURCE, IMPORT_DESTINATION, PICK_YT }

private enum class ImportSource { FILE, YT_MUSIC, SPOTIFY }

/**
 * Making a playlist, as a few small decisions in the order they are made instead of one list of everything:
 *
 * 1. Make a new one, or bring one in.
 * 2. New: its name, and where to keep it.
 *    Import: from a file, from YouTube Music or from Spotify — then, whichever it was, where to keep the result.
 *
 * Each step is its own card with only its own choices, an icon on every one, and a way back. What leaves the flow is
 * a finished decision — the callbacks below — and the host does the work.
 */
@Composable
fun NewPlaylistFlow(
    hazeState: HazeState,
    signedIn: Boolean,
    /** The account's playlists, for importing one of them. */
    accountPlaylists: List<UserPlaylist>,
    onCreate: (name: String, destination: PlaylistDestination, privacy: PlaylistPrivacy) -> Unit,
    onImportFile: (PlaylistDestination, PlaylistPrivacy) -> Unit,
    onImportSpotify: (PlaylistDestination, PlaylistPrivacy) -> Unit,
    onImportYouTubeMusic: (UserPlaylist, PlaylistDestination, PlaylistPrivacy) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(FlowStep.ROOT) }
    var source by remember { mutableStateOf(ImportSource.FILE) }
    var destination by remember { mutableStateOf(PlaylistDestination.DEVICE) }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var name by remember { mutableStateOf("") }

    val back: () -> Unit = {
        step = when (step) {
            FlowStep.NEW, FlowStep.IMPORT_SOURCE -> FlowStep.ROOT
            FlowStep.IMPORT_DESTINATION -> FlowStep.IMPORT_SOURCE
            FlowStep.PICK_YT -> FlowStep.IMPORT_DESTINATION
            FlowStep.ROOT -> FlowStep.ROOT
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, width = 320.dp) {
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "new_playlist_flow",
        ) { current ->
            Column(Modifier.fillMaxWidth()) {
                when (current) {
                    FlowStep.ROOT -> {
                        Header(stringResource(R.string.new_playlist), null)
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionCard(Icons.Rounded.Add, stringResource(R.string.flow_create_new), stringResource(R.string.flow_create_new_sub)) { step = FlowStep.NEW }
                            OptionCard(Icons.Rounded.Download, stringResource(R.string.flow_import), stringResource(R.string.flow_import_sub)) { step = FlowStep.IMPORT_SOURCE }
                        }
                        Spacer(Modifier.height(12.dp))
                        AlertRule()
                        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
                    }

                    FlowStep.NEW -> {
                        Header(stringResource(R.string.flow_create_new), back)
                        Column(Modifier.padding(horizontal = 14.dp)) {
                            PillTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = stringResource(R.string.playlist_name),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {}),
                            )
                            Spacer(Modifier.height(14.dp))
                            DestinationPicker(signedIn, destination, privacy, { destination = it }, { privacy = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        AlertRule()
                        AlertAction(
                            label = stringResource(R.string.create_playlist),
                            emphasised = true,
                            enabled = name.isNotBlank(),
                            onClick = animatedExit { onCreate(name.trim(), destination, privacy); onDismiss() },
                        )
                        AlertRule()
                        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
                    }

                    FlowStep.IMPORT_SOURCE -> {
                        Header(stringResource(R.string.flow_import_from), back)
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionCard(Icons.Rounded.Description, stringResource(R.string.flow_src_file), stringResource(R.string.flow_src_file_sub)) {
                                source = ImportSource.FILE; step = FlowStep.IMPORT_DESTINATION
                            }
                            OptionCard(
                                icon = vectorResource(Res.drawable.ytmusic_mono),
                                title = stringResource(R.string.flow_dest_yt),
                                subtitle = stringResource(if (signedIn) R.string.flow_src_yt_sub else R.string.flow_sign_in_needed),
                                enabled = signedIn,
                            ) { source = ImportSource.YT_MUSIC; step = FlowStep.IMPORT_DESTINATION }
                            OptionCard(vectorResource(Res.drawable.spotify_mono), stringResource(R.string.flow_src_spotify), stringResource(R.string.flow_src_spotify_sub)) {
                                source = ImportSource.SPOTIFY; step = FlowStep.IMPORT_DESTINATION
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AlertRule()
                        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
                    }

                    FlowStep.IMPORT_DESTINATION -> {
                        Header(stringResource(R.string.flow_import_to), back)
                        Column(Modifier.padding(horizontal = 14.dp)) {
                            DestinationPicker(signedIn, destination, privacy, { destination = it }, { privacy = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        AlertRule()
                        AlertAction(
                            label = stringResource(R.string.flow_continue),
                            emphasised = true,
                            onClick = {
                                when (source) {
                                    ImportSource.YT_MUSIC -> step = FlowStep.PICK_YT
                                    ImportSource.FILE -> { onImportFile(destination, privacy); onDismiss() }
                                    ImportSource.SPOTIFY -> { onImportSpotify(destination, privacy); onDismiss() }
                                }
                            },
                        )
                        AlertRule()
                        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
                    }

                    FlowStep.PICK_YT -> {
                        Header(stringResource(R.string.flow_pick_playlist), back)
                        if (accountPlaylists.isEmpty()) {
                            Text(
                                stringResource(R.string.flow_no_playlists),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                                items(accountPlaylists, key = { it.playlistId }) { playlist ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onImportYouTubeMusic(playlist, destination, privacy); onDismiss() }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        AsyncImage(
                                            model = playlist.thumbnailUrl.artworkAt(ROW_ART_PX),
                                            contentDescription = null,
                                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(playlist.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                            if (playlist.subtitle.isNotBlank()) {
                                                Text(playlist.subtitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        AlertRule()
                        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = animatedExit(onDismiss))
                    }
                }
            }
        }
    }
}

/** The title of a step, with the way back to the one before it when there is one. */
@Composable
private fun Header(title: String, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(SfIcons.ArrowBack, stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** One choice: its icon in a tinted tile, what it is called and one line on what it does. */
@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(
                if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(subtitle, fontSize = 13.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .then(if (selected) Modifier else Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))),
            )
        } else if (enabled) {
            Icon(SfIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

/** Where to keep it: on this device or on the account — and, for the account, who can see it. */
@Composable
private fun DestinationPicker(
    signedIn: Boolean,
    destination: PlaylistDestination,
    privacy: PlaylistPrivacy,
    onDestination: (PlaylistDestination) -> Unit,
    onPrivacy: (PlaylistPrivacy) -> Unit,
) {
    Text(
        stringResource(R.string.flow_save_to).uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OptionCard(
            Icons.Rounded.PhoneIphone, stringResource(R.string.flow_dest_device), stringResource(R.string.flow_dest_device_sub),
            selected = destination == PlaylistDestination.DEVICE,
        ) { onDestination(PlaylistDestination.DEVICE) }
        OptionCard(
            Icons.Rounded.Public, stringResource(R.string.flow_dest_yt),
            stringResource(if (signedIn) R.string.flow_dest_yt_sub else R.string.flow_sign_in_needed),
            enabled = signedIn,
            selected = destination == PlaylistDestination.YT_MUSIC,
        ) { onDestination(PlaylistDestination.YT_MUSIC) }
    }
    if (destination == PlaylistDestination.YT_MUSIC && signedIn) {
        Text(
            stringResource(R.string.who_can_see_it).uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaylistPrivacy.entries.filter { it != PlaylistPrivacy.DEVICE }.forEach { option ->
                val on = option == privacy
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                        .clickable { onPrivacy(option) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
