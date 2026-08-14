package dev.gr0mi4.ohealthinsights

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** WorkManager keeps the schedule across reboots, so no BOOT_COMPLETED receiver is needed. */
object AutoSyncScheduler {
    fun update(context: Context, enabled: Boolean) {
        if (enabled) schedule(context) else cancel(context)
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(
            repeatInterval = 1,
            repeatIntervalTimeUnit = TimeUnit.DAYS,
            flexTimeInterval = 4,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        // KEEP, so saving settings does not push the next run a further day out.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private const val WORK_NAME = "ohealth-auto-sync"
}
