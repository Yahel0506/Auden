package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * How a device folder's cover looks: the three stops of its diagonal gradient, light at the top left to deep at the
 * bottom right as Apple Music's own list covers are, and its glyph. [top] and [bottom] are the ends, for whatever
 * reads the colour off the cover (the page's mesh).
 */
class FolderCoverStyle(val colors: List<Color>, val icon: ImageVector) {
    val top: Color get() = colors.first()
    val bottom: Color get() = colors.last()
}

/**
 * The filled glyph each folder's cover wears, the same symbol its row on the Library draws in outline. Filled in by
 * the app at startup with its SF Symbols; the shared module only has Material's, which stand in until then.
 */
object FolderCoverGlyphs {
    @Volatile
    var of: (String?) -> ImageVector? = { null }
}

/** The cover style of a device folder's browse id (and of Mis favoritas, drawn the same way), or null for anything else. */
fun folderCoverStyle(browseId: String?): FolderCoverStyle? {
    val (colors, fallback) = when (browseId) {
        // Downloads: a clear sky blue settling into a deep indigo.
        "local:downloads" -> listOf(Color(0xFF6CC8FF), Color(0xFF2F7DF6), Color(0xFF3D3FC9)) to Icons.Rounded.Download
        // Local Music: warm amber into coral and a rose red, the colour of things kept close.
        "local:all" -> listOf(Color(0xFFFFC56B), Color(0xFFFF8048), Color(0xFFE8456B)) to Icons.Rounded.Folder
        // The song cache: lavender into violet and a deep plum.
        "local:cache" -> listOf(Color(0xFFC9A2FF), Color(0xFF8C5CF6), Color(0xFF5232C2)) to Icons.Rounded.Storage
        // WebDAV: mint into a sea teal.
        "local:webdav" -> listOf(Color(0xFF7CEBD3), Color(0xFF22B7B0), Color(0xFF16739A)) to Icons.Rounded.Cloud
        // Mis favoritas, what is liked on this device: a pink blush into a warm rose red, as Apple's Favourite Songs.
        "local:offline:liked" -> listOf(Color(0xFFFF9BB3), Color(0xFFFF4F79), Color(0xFFD81F55)) to Icons.Rounded.Star
        // SMB: a cool slate.
        "local:smb" -> listOf(Color(0xFFA9BBD2), Color(0xFF6A82A3), Color(0xFF34465F)) to Icons.Rounded.Lan
        else -> return null
    }
    return FolderCoverStyle(colors, FolderCoverGlyphs.of(browseId) ?: fallback)
}

/**
 * A device folder's cover, after Apple Music's: a soft diagonal gradient with a light sheen across its top left and a
 * large white glyph, slightly lifted off it. The same on the card and on the page.
 */
@Composable
fun FolderCover(browseId: String?, modifier: Modifier = Modifier) {
    val style = folderCoverStyle(browseId) ?: return
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.linearGradient(
                    colors = style.colors,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            // The sheen: a wide, faint light from the top left, so the colour has depth rather than lying flat.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.08f),
                    radius = size.maxDimension * 0.85f,
                ),
            )
            // And a little dusk along the bottom edge.
            drawRect(
                Brush.verticalGradient(
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.12f),
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.4f),
        )
    }
}
