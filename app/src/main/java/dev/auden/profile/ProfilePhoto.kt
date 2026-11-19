package dev.auden.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * The profile's photo, kept on the device: the picked image scaled down to a square-ish 512 px at most and written
 * under `filesDir/profile`, so it outlives the gallery's copy and never leaves the phone (only an https photo is ever
 * sent to a party, see [AudenProfile.sharablePhoto]).
 */
object ProfilePhoto {
    private const val MAX_SIDE = 512

    /** Saves [uri] and returns the absolute path, or null when it cannot be read as an image. Call off the main thread. */
    fun save(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        // A new name each time, so the image cache does not keep showing the old photo under the same path.
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        file.absolutePath
    }.getOrNull()

    /** Removes the saved photo files. */
    fun clear(context: Context) {
        File(context.filesDir, "profile").listFiles()?.forEach { it.delete() }
    }

    /** The model Coil should be given for [photo]: an https URL as it is, a saved file as a [File]. */
    fun model(photo: String?): Any? = photo?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else File(it) }
}
