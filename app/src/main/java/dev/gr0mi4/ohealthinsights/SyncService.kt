package dev.gr0mi4.ohealthinsights

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns a running sync so it outlives the screen.
 *
 * A foreground service is what keeps the process alive and off the doze path for the length of an
 * export, which for a full history is minutes of continuous reading. It cannot ask the user
 * anything - authorisation is resolved before it starts - and it hands the result back through
 * [SyncSession] rather than acting on it, because saving an export to a location of the user's
 * choosing needs an Activity.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            stop()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        val diagnostic = intent?.getBooleanExtra(EXTRA_DIAGNOSTIC, false) ?: false
        val startedAt = SystemClock.elapsedRealtime()
        val initialStage =
            if (diagnostic) "Starting full raw diagnostic export" else "Starting compact sync"

        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(initialStage),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        SyncSession.started(diagnostic, initialStage, startedAt)

        job = scope.launch {
            val outcome = runCatching {
                SyncCoordinator(applicationContext).run(
                    client = HealthConnectClient.getOrCreate(applicationContext),
                    diagnostic = diagnostic,
                    onStage = { stage ->
                        SyncSession.stage(stage)
                        publish(stage)
                    },
                    // The service has no screen to put a consent dialog on. The caller resolves
                    // authorisation first, so by here a cached grant either works or the upload
                    // fails with needsAuthorization and the user is told.
                    launchAuth = null,
                )
            }.getOrElse { error -> SyncOutcome.ExportFailed(error) }

            SyncSession.finished(outcome)
            stop()
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun publish(stage: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(stage))
    }

    private fun notification(stage: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("OHealth Insights is syncing")
        .setContentText(stage)
        .setStyle(NotificationCompat.BigTextStyle().bigText(stage))
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while an export is running." },
        )
    }

    companion object {
        private const val CHANNEL_ID = "ohealth_sync_progress"
        private const val NOTIFICATION_ID = 2001
        private const val EXTRA_DIAGNOSTIC = "diagnostic"
        private const val ACTION_CANCEL = "dev.gr0mi4.ohealthinsights.CANCEL_SYNC"

        fun start(context: Context, diagnostic: Boolean) {
            val intent = Intent(context, SyncService::class.java)
                .putExtra(EXTRA_DIAGNOSTIC, diagnostic)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
