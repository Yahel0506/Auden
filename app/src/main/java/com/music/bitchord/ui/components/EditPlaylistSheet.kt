package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt

/**
 * Everything about a playlist that is the listener's to change, in one place instead of a row each on its menu: the
 * cover — chosen from a photo, made in the cover creator, or taken off — its name and, for a list kept on this
 * device, a description. The three change things differently, so they are three cards, not three rows.
 *
 * The cover answers at once, since it is made elsewhere and comes back; the name and the description are kept
 * together by Save. A playlist on an account can only be renamed, so the other cards are not drawn for it.
 */
@Composable
fun EditPlaylistSheet(
    name: String,
    description: String,
    coverUrl: String?,
    /** Whether the cover can be set at all: only for a playlist kept on this device. */
    canEditCover: Boolean,
    /** Whether the cover is the listener's own, which is what "Remove" takes off. */
    hasCustomCover: Boolean,
    canEditDescription: Boolean,
    onChoosePhoto: () -> Unit,
    onCreateCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onSave: (name: String, description: String) -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember { mutableStateOf(name) }
    var note by remember { mutableStateOf(description) }
    val changed = title.trim() != name.trim() || note.trim() != description.trim()
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground) }
            Text(
                stringResource(R.string.edit_playlist),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSave(title.trim().ifBlank { name }, note.trim()) }, enabled = changed && title.isNotBlank()) {
                Text(stringResource(R.string.save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        if (canEditCover) {
            SectionLabel(stringResource(R.string.cover))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(112.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverUrl != null) {
                        AsyncImage(
                            model = coverUrl.artworkAt(ROW_ART_PX * 2),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    }
                }
                // The ways to give it one, as the rows of a card: each is a different errand.
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    CardRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.cover_choose_photo), onChoosePhoto)
                    CardDivider()
                    CardRow(Icons.Rounded.AutoAwesome, stringResource(R.string.cover_creator_title), onCreateCover)
                    if (hasCustomCover) {
                        CardDivider()
                        CardRow(Icons.Rounded.HideImage, stringResource(R.string.remove_cover), onRemoveCover, destructive = true)
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.playlist_name))
        Field(title, { title = it }, singleLine = true)

        if (canEditDescription) {
            SectionLabel(stringResource(R.string.playlist_description))
            Field(note, { note = it }, singleLine = false, placeholder = stringResource(R.string.playlist_description_hint))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun CardRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 15.sp, color = tint, maxLines = 1)
    }
}

@Composable
private fun CardDivider() = HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 46.dp))

@Composable
private fun Field(value: String, onChange: (String) -> Unit, singleLine: Boolean, placeholder: String = "") {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
