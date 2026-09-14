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
        when (val precondition = precondition(context)) {
            // The user turned this off. Nothing to report.
            is Precondition.Disabled -> return Result.success()

            // Retrying will not help - someone has to grant something - but staying silent left
            // automatic sync reporting success while doing nothing, every day, indefinitely.
            is Precondition.NeedsAttention -> {
                SyncNotifications.notifyBlocked(context, precondition.reason)
                return Result.success()
            }

            Precondition.Ready -> Unit
        }

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

    /**
     * Whether this run can proceed, separating a switch the user turned off from something that
     * needs their attention. The distinction is the point: both used to return a bare success.
     */
    private sealed interface Precondition {
        data object Ready : Precondition

        data class Disabled(val reason: String) : Precondition

        data class NeedsAttention(val reason: String) : Precondition
    }

    private suspend fun precondition(context: Context): Precondition {
        val settings = DriveSettingsStore(context).load()
        if (!settings.autoSyncEnabled) return Precondition.Disabled("Automatic sync is disabled")
        if (!settings.autoUploadEnabled) return Precondition.Disabled("Drive auto-upload is disabled")

        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return Precondition.NeedsAttention("Health Connect is unavailable on this device.")
        }
        if (!settings.driveAuthorizationGranted) {
            return Precondition.NeedsAttention("Google Drive access was lost. Reconnect it to resume uploads.")
        }
        if (!SyncStateStore(context).hasCheckpoint()) {
            return Precondition.NeedsAttention("Run a full sync from the app once; daily sync continues from there.")
        }

        val granted = runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        if (HealthExportEngine.backgroundPermission !in granted) {
            return Precondition.NeedsAttention("Background read access is not granted, so sync cannot run on its own.")
        }
        return Precondition.Ready
    }

    private companion object {
        const val maxAttempts = 3
    }
}
