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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { ProfilePhoto.save(context, uri) }
            if (saved != null) ProfileStore.setPhoto(saved)
        }
    }
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
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
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
