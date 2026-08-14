package dev.gr0mi4.ohealthinsights

import android.Manifest
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
import dev.gr0mi4.ohealthinsights.drive.SettingsActivity

/**
 * The background worker has no screen, so anything that needs the user surfaces here. Success is
 * deliberately silent: only a repeated failure or a lost Drive grant is worth interrupting for.
 */
object SyncNotifications {
    fun notifyRepeatedFailure(context: Context, reason: String) {
        show(
            context = context,
            id = FAILURE_ID,
            title = "Automatic sync is failing",
            text = reason,
            target = Intent(context, MainActivity::class.java),
        )
    }

    fun notifyAuthorizationRequired(context: Context) {
        show(
            context = context,
            id = RECONNECT_ID,
            title = "Reconnect Google Drive",
            text = "Automatic sync cannot upload until Drive access is granted again.",
            target = Intent(context, SettingsActivity::class.java),
        )
    }

    fun clearAll(context: Context) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).apply {
            cancel(FAILURE_ID)
            cancel(RECONNECT_ID)
        }
    }

    private fun show(context: Context, id: Int, title: String, text: String, target: Intent) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context,
            id,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Automatic sync",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Problems that stop the daily Health Connect sync."
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private const val CHANNEL_ID = "ohealth_sync"
    private const val FAILURE_ID = 1001
    private const val RECONNECT_ID = 1002
}
