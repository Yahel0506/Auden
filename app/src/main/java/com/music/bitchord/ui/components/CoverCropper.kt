package com.music.bitchord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Frames a picture as a playlist's cover: the picture under a square window,
 * dragged and pinched until the part wanted is inside it.
 *
 * The picture can never leave a gap in the window — it is held at least as
 * large as the square and its edges stop at the square's — so whatever is
 * confirmed is a full cover. [onCropped] gets that square, no larger than
 * [outputSide] on a side; [onFailed] is for a picture that could not be read.
 */
@Composable
fun CoverCropper(
    uri: Uri,
    outputSide: Int,
    onCancel: () -> Unit,
    onCropped: (Bitmap) -> Unit,
    onFailed: () -> Unit,
    /** A round frame, for a picture that is worn in a circle (the profile's photo): the saved picture is still the square. */
    circular: Boolean = false,
    @androidx.annotation.StringRes title: Int = R.string.cover_crop_title,
) {
    val context = LocalContext.current
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        val decoded = withContext(Dispatchers.IO) { runCatching { decodeForCrop(context, uri) }.getOrNull() }
        if (decoded == null) onFailed() else source = decoded
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Zoom past the size at which the picture just fills the square, and the picture's centre
        // away from the square's — in screen pixels.
        var zoom by remember(uri) { mutableFloatStateOf(1f) }
        var offset by remember(uri) { mutableStateOf(Offset.Zero) }
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        val margin = with(LocalDensity.current) { 24.dp.toPx() }
        val window = (min(canvasSize.width, canvasSize.height) - margin * 2).coerceAtLeast(1f)
        val picture = source

        // Pixels of screen per pixel of picture at zoom 1: the square is just covered.
        val fill = if (picture == null) 1f else max(window / picture.width, window / picture.height)
        fun clamp(candidate: Offset, atZoom: Float): Offset {
            if (picture == null) return Offset.Zero
            val slackX = ((picture.width * fill * atZoom) - window) / 2f
            val slackY = ((picture.height * fill * atZoom) - window) / 2f
            return Offset(candidate.x.coerceIn(-slackX, slackX), candidate.y.coerceIn(-slackY, slackY))
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(SfIcons.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                }
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = picture != null,
                    onClick = {
                        picture?.let { onCropped(cropSquare(it, fill * zoom, offset, window, outputSide)) }
                    },
                ) {
                    Icon(
                        SfIcons.Check,
                        contentDescription = stringResource(R.string.save),
                        tint = if (picture != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // The picture is drawn larger than the area it is framed in; without this it spills
                    // over the bar above and the hint below, and covers their buttons.
                    .clipToBounds()
                    .onSizeChanged { canvasSize = it },
                contentAlignment = Alignment.Center,
            ) {
                if (picture == null) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                } else {
                    val image = remember(picture) { picture.asImageBitmap() }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(picture, window) {
                                detectTransformGestures { _, pan, pinch, _ ->
                                    val next = (zoom * pinch).coerceIn(1f, MAX_ZOOM)
                                    // Scaled about the square's centre, so what is in the middle stays there.
                                    offset = clamp(offset * (next / zoom) + pan, next)
                                    zoom = next
                                }
                            },
                    ) {
                        val scale = fill * zoom
                        val drawn = Size(picture.width * scale, picture.height * scale)
                        val topLeft = Offset(
                            center.x + offset.x - drawn.width / 2f,
                            center.y + offset.y - drawn.height / 2f,
                        )
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                            dstSize = IntSize(drawn.width.roundToInt(), drawn.height.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                        // Everything outside the square is dimmed rather than hidden: what is being
                        // left out is how the listener judges where to put the square.
                        val square = Rect(
                            Offset(center.x - window / 2f, center.y - window / 2f),
                            Size(window, window),
                        )
                        val corner = CornerRadius(12.dp.toPx())
                        val scrim = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(Offset.Zero, size))
                            if (circular) addOval(square) else addRoundRect(RoundRect(square, corner))
                        }
                        drawPath(scrim, Color.Black.copy(alpha = 0.62f))
                        if (circular) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.9f),
                                radius = window / 2f,
                                center = square.center,
                                style = Stroke(1.5.dp.toPx()),
                            )
                            return@Canvas
                        }
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = square.topLeft,
                            size = square.size,
                            cornerRadius = corner,
                            style = Stroke(1.5.dp.toPx()),
                        )
                        val third = window / 3f
                        val rule = Color.White.copy(alpha = 0.28f)
                        for (i in 1..2) {
                            drawLine(
                                rule,
                                Offset(square.left + third * i, square.top),
                                Offset(square.left + third * i, square.bottom),
                                strokeWidth = 1.dp.toPx(),
                            )
                            drawLine(
                                rule,
                                Offset(square.left, square.top + third * i),
                                Offset(square.right, square.top + third * i),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.cover_crop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            )
        }
    }
}

private const val MAX_ZOOM = 6f

/** The longest side the picture is held at while it is being framed; more only costs memory. */
private const val DECODE_SIDE = 2400

/**
 * The part of [picture] inside the square: [scale] is screen pixels per picture
 * pixel, [offset] how far the picture's centre sits from the square's, and
 * [window] the square's side on screen.
 */
private fun cropSquare(picture: Bitmap, scale: Float, offset: Offset, window: Float, outputSide: Int): Bitmap {
    val side = (window / scale).roundToInt().coerceIn(1, min(picture.width, picture.height))
    val left = (picture.width / 2f - offset.x / scale - side / 2f).roundToInt().coerceIn(0, picture.width - side)
    val top = (picture.height / 2f - offset.y / scale - side / 2f).roundToInt().coerceIn(0, picture.height - side)
    val square = Bitmap.createBitmap(picture, left, top, side, side)
    return if (side > outputSide) Bitmap.createScaledBitmap(square, outputSide, outputSide, true) else square
}

/** Reads the picture upright and no larger than [DECODE_SIDE] on its longest side. */
private fun decodeForCrop(context: Context, uri: Uri): Bitmap? {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // Turns the picture the way its EXIF tag says, which BitmapFactory leaves to the caller.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > DECODE_SIDE) {
                val ratio = DECODE_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > DECODE_SIDE) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val degrees = resolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (degrees == 0f) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
}
