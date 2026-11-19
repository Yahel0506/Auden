package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.icons.SfIcons

/**
 * What the Library's ⋯ offers: to edit the pinned lists, or the sections. The body of the menu that hangs off the ⋯, as
 * the playlists' own does ([BrowseActionsMenu]).
 */
@Composable
fun LibraryOptionsMenu(
    onEditPinned: () -> Unit,
    onEditSections: () -> Unit,
) {
    CompositionLocalProvider(LocalActionRowStyle provides ActionRowStyle.Menu) {
        Column(Modifier.padding(vertical = 8.dp)) {
            IosMenuRow(
                label = stringResource(R.string.edit_pinned),
                icon = SfIcons.Pin,
                onClick = onEditPinned,
            )
            IosMenuRow(
                label = stringResource(R.string.edit_sections),
                icon = SfIcons.Sliders,
                onClick = onEditSections,
            )
        }
    }
}
