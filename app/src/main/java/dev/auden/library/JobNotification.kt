package dev.auden.library

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R

/**
 * One long-running library job's notification: an ongoing progress bar while it
 * works, replaced by a plain note of how it ended. Quiet by design (low
 * importance, no sound), since it reports progress rather than news.
 */
class JobNotification(private val context: Context, private val id: Int, private val title: String) {
    private var lastUpdate = 0L

    init { ensureChannel(context) }

    fun indeterminate(text: String) = post(builder(text).setProgress(0, 0, true))

    /** Throttled: dozens of updates a second would only make the shade stutter. */
    fun progress(text: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (done < total && now - lastUpdate < 400) return
        lastUpdate = now
        post(builder(text).setProgress(total, done, false))
    }

    fun finish(finalTitle: String, text: String) = post(
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(finalTitle)
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp()),
    )

    private fun builder(text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_logo)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(openApp())

    private fun openApp() = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun post(builder: NotificationCompat.Builder) {
        // Without the permission the in-app state still shows progress; nothing else to do.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    companion object {
        private const val CHANNEL_ID = "library_save"

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.library_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.library_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
