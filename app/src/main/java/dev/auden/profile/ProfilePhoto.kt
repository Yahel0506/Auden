package dev.auden.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
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
        val scaled = decode(context, uri) ?: return null
        val dir = File(context.filesDir, "profile").apply { mkdirs() }
        // A new name each time, so the image cache does not keep showing the old photo under the same path.
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() }
        file.absolutePath
    }.getOrNull()

    /** The image at most [MAX_SIDE] px on its long side, turned as it was taken: a camera photo carries its turn in EXIF. */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the EXIF orientation itself.
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / long
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
    }

    /** Removes the saved photo files. */
    fun clear(context: Context) {
        File(context.filesDir, "profile").listFiles()?.forEach { it.delete() }
    }

    /**
     * The model Coil should be given for [photo]: an https URL as it is, a saved file as a [File]. A path whose file is not
     * there — the profile came back from a backup, which does not carry the image — is no photo at all.
     */
    fun model(photo: String?): Any? = photo?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else File(it).takeIf(File::exists)
    }
}
