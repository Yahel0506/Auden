package com.music.bitchord.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.ui.components.PillTextField
import com.music.bitchord.ui.icons.SfIcons
import dev.auden.profile.AudenProfile
import dev.auden.profile.ProfilePhoto
import dev.auden.profile.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Auden profile, edited where the account's settings are: its name — what people see in parties and in Connect —
 * and an optional photo, kept on the device. A name cannot be left empty: the profile always has one.
 */
@Composable
internal fun ProfileEditGroup() {
    val profile by ProfileStore.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = profile
    val pickPhoto = dev.auden.profile.rememberProfilePhotoPicker()
    // Renaming moves the local room of someone with no Google account and nothing frozen: say so, only then.
    val movesRoom = current != null && current.connectRoom == null
    SettingsGroup(
        header = stringResource(R.string.profile_header),
        footer = stringResource(if (movesRoom) R.string.profile_name_footer_room else R.string.profile_name_footer),
    ) {
        var name by remember { mutableStateOf(ListenTogether.nickname().ifBlank { current?.takeUnless { it.nameIsDefault }?.name.orEmpty() }) }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            PillTextField(
                value = name,
                onValueChange = {
                    name = it.take(80)
                    if (name.isNotBlank()) ProfileStore.setName(name)
                },
                placeholder = current?.takeUnless { it.nameIsDefault }?.name ?: stringResource(R.string.profile_default_name),
                container = MaterialTheme.colorScheme.surfaceVariant,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        FullWidthDivider()
        val photoModel = ProfilePhoto.model(current?.photo)
        SettingsRow(
            title = stringResource(R.string.profile_photo),
            subtitle = stringResource(if (photoModel == null) R.string.profile_photo_none else R.string.profile_photo_change),
            iconContent = {
                if (photoModel != null) {
                    AsyncImage(model = photoModel, contentDescription = null, modifier = Modifier.size(34.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(SfIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            },
            onClick = pickPhoto,
        )
        if (photoModel != null) {
            FullWidthDivider()
            DestructiveRow(label = stringResource(R.string.profile_photo_remove), onClick = {
                ProfilePhoto.clear(context)
                ProfileStore.setPhoto(null)
            })
        }
    }
}

/**
 * The head of the account page: the Auden profile as the app shows it everywhere (its photo, or its initial, and its
 * name). Editing is the group under it; nothing here belongs to YouTube Music or Spotify.
 */
@Composable
internal fun AudenProfileCard() {
    val profile = dev.auden.profile.rememberProfileDisplay()
    val photo = ProfilePhoto.model(profile.photo)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            AsyncImage(model = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(64.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) {
                if (profile.isPlaceholder) {
                    Icon(SfIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                } else {
                    androidx.compose.material3.Text(
                        profile.name.trim().take(1).uppercase(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                profile.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            androidx.compose.material3.Text(
                stringResource(R.string.auden_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
