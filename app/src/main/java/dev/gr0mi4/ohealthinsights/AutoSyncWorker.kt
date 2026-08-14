package dev.gr0mi4.ohealthinsights

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.gr0mi4.ohealthinsights.drive.DriveSettingsStore

/**
 * Daily incremental sync. Only ever runs the compact path: a first full history export can take
 * longer than the ten minutes WorkManager allows, so it stays a manual action in the app.
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (blockingPrecondition(context) != null) return Result.success()

        val outcome = SyncCoordinator(context).run(
            client = HealthConnectClient.getOrCreate(context),
            diagnostic = false,
            onStage = {},
            launchAuth = null,
        )

        return when (outcome) {
            is SyncOutcome.Uploaded -> {
                SyncNotifications.clearAll(context)
                Result.success()
            }

            is SyncOutcome.UploadFailed -> {
                // Nobody can pick a save location in the background, and the checkpoint has not
                // moved, so the next run re-exports the same range.
                outcome.file.delete()
                if (outcome.needsAuthorization) {
                    SyncNotifications.notifyAuthorizationRequired(context)
                    Result.failure()
                } else {
                    retryOrReport(context, outcome.error.message ?: outcome.error.javaClass.simpleName)
                }
            }

            is SyncOutcome.ExportFailed ->
                retryOrReport(context, outcome.error.message ?: outcome.error.javaClass.simpleName)

            is SyncOutcome.ReadyToSave -> {
                outcome.file.delete()
                Result.success()
            }
        }
    }

    private fun retryOrReport(context: Context, reason: String): Result =
        if (runAttemptCount + 1 < maxAttempts) {
            Result.retry()
        } else {
            SyncNotifications.notifyRepeatedFailure(context, reason)
            Result.failure()
        }

    /** Returns why this run cannot proceed, or null when everything is in place. */
    private suspend fun blockingPrecondition(context: Context): String? {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return "Health Connect is unavailable"
        }
        val settings = DriveSettingsStore(context).load()
        if (!settings.autoSyncEnabled) return "Automatic sync is disabled"
        if (!settings.autoUploadEnabled) return "Drive auto-upload is disabled"
        if (!settings.driveAuthorizationGranted) return "Drive is not authorized"
        if (!SyncStateStore(context).hasCheckpoint()) return "First full sync must run from the app"

        val granted = runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        if (HealthExportEngine.backgroundPermission !in granted) {
            return "Background read access is not granted"
        }
        return null
    }

    private companion object {
        const val maxAttempts = 3
    }
}
