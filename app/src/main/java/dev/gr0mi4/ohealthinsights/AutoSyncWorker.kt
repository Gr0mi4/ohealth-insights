package dev.gr0mi4.ohealthinsights

import android.content.Context
import android.util.Log
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
        blockingPrecondition(context)?.let { reason ->
            Log.i(LOG_TAG, "Skipping automatic sync: $reason")
            return Result.success()
        }

        val outcome = SyncCoordinator(context).runIfIdle(
            client = HealthConnectClient.getOrCreate(context),
            diagnostic = false,
            onStage = {},
            launchAuth = null,
        ) ?: return Result.retry() // The app is syncing right now; try again later.

        return when (outcome) {
            is SyncOutcome.Uploaded -> when {
                outcome.summary.checkpointHeldBack ->
                    // The upload worked but the sync did not move forward, so tomorrow repeats
                    // today. Harmless once or twice, worth reporting if it persists.
                    retryOrReport(context, outcome.summary.warnings.firstOrNull() ?: "A record type could not be read")

                !outcome.checkpointSaved ->
                    retryOrReport(context, "The sync checkpoint could not be saved")

                else -> {
                    SyncNotifications.clearAll(context)
                    Result.success()
                }
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
                // Unreachable while the preconditions below hold. If it does happen, the export has
                // nowhere to go and staying quiet would look like a working daily sync.
                outcome.file.delete()
                SyncNotifications.notifyRepeatedFailure(context, "Automatic upload is not available for this build.")
                Result.failure()
            }
        }
    }

    private fun retryOrReport(context: Context, reason: String): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
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
        val settingsStore = DriveSettingsStore(context)
        // Without a client ID the upload can never start, and the export would be built and thrown
        // away on every run.
        if (!settingsStore.isConfigured()) return "This build has no Drive OAuth client ID"

        val settings = settingsStore.load()
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
        const val MAX_ATTEMPTS = 3
        const val LOG_TAG = "AutoSyncWorker"
    }
}
