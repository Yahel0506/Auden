package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.bitchord.ui.icons.BitChordIcons

/** How a device folder's cover looks: two colours for its gradient, and its glyph. */
class FolderCoverStyle(val top: Color, val bottom: Color, val icon: ImageVector)

/** The cover style of a device folder's browse id, or null for anything that is not one of the folders. */
fun folderCoverStyle(browseId: String?): FolderCoverStyle? = when (browseId) {
    "local:downloads" -> FolderCoverStyle(Color(0xFF5AA0FF), Color(0xFF2456D6), BitChordIcons.Download)
    "local:all" -> FolderCoverStyle(Color(0xFF45D9A0), Color(0xFF0E8A6A), Icons.Rounded.LibraryMusic)
    "local:cache" -> FolderCoverStyle(Color(0xFFB98CFF), Color(0xFF4F2AA8), Icons.Rounded.Cached)
    "local:webdav" -> FolderCoverStyle(Color(0xFFFF8F7A), Color(0xFFA6325A), Icons.Rounded.Folder)
    "local:smb" -> FolderCoverStyle(Color(0xFF6E8CA8), Color(0xFF1E3347), Icons.Rounded.Storage)
    else -> null
}

/** A device folder's cover: a plain gradient of its own colour with its glyph in the middle. The same on the card and on the page. */
@Composable
fun FolderCover(browseId: String?, modifier: Modifier = Modifier) {
    val style = folderCoverStyle(browseId) ?: return
    Box(
        modifier.background(Brush.verticalGradient(listOf(style.top, style.bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}
