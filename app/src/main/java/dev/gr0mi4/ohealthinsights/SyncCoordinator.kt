package dev.gr0mi4.ohealthinsights

import android.content.Context
import androidx.activity.result.IntentSenderRequest
import androidx.health.connect.client.HealthConnectClient
import com.google.android.gms.auth.api.identity.AuthorizationResult
import dev.gr0mi4.ohealthinsights.drive.DriveAuthorizationRequired
import dev.gr0mi4.ohealthinsights.drive.DriveSettingsStore
import dev.gr0mi4.ohealthinsights.drive.DriveUploader
import dev.gr0mi4.ohealthinsights.drive.MetricsStore
import dev.gr0mi4.ohealthinsights.drive.ReportBuilder
import dev.gr0mi4.ohealthinsights.drive.ReportCollector
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a full sync: export, optional Drive upload, checkpoint advance.
 *
 * Holds no reference to a UI, so the same code path serves the manual button and the background
 * worker. The caller decides what to do with a [SyncOutcome]; the coordinator only guarantees that
 * the checkpoint advances after a successful upload and never before.
 */
class SyncCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val syncState = SyncStateStore(appContext)
    private val driveSettingsStore = DriveSettingsStore(appContext)
    private val metricsStore = MetricsStore(appContext)
    private val driveUploader = DriveUploader(
        appContext,
        driveSettingsStore,
        ReportBuilder(metricsStore),
    )

    fun canAutoUpload(diagnostic: Boolean): Boolean =
        driveUploader.canAutoUpload(driveSettingsStore.load(), diagnostic)

    /** Waits for any sync already in flight, then runs. Used by the manual button. */
    suspend fun run(
        client: HealthConnectClient,
        diagnostic: Boolean,
        onStage: (String) -> Unit,
        launchAuth: (suspend (IntentSenderRequest) -> AuthorizationResult?)?,
    ): SyncOutcome = syncMutex.withLock {
        execute(client, diagnostic, SyncTrigger.MANUAL, onStage, launchAuth)
    }

    /**
     * Runs only if nothing else is syncing, otherwise returns null. The background worker uses this
     * so it never races the screen the user is looking at.
     */
    suspend fun runIfIdle(
        client: HealthConnectClient,
        diagnostic: Boolean,
        onStage: (String) -> Unit,
        launchAuth: (suspend (IntentSenderRequest) -> AuthorizationResult?)?,
    ): SyncOutcome? {
        if (!syncMutex.tryLock()) return null
        return try {
            execute(client, diagnostic, SyncTrigger.AUTOMATIC, onStage, launchAuth)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun execute(
        client: HealthConnectClient,
        diagnostic: Boolean,
        trigger: SyncTrigger,
        onStage: (String) -> Unit,
        launchAuth: (suspend (IntentSenderRequest) -> AuthorizationResult?)?,
    ): SyncOutcome {
        val file = File(appContext.cacheDir, "ohealth-insights-${System.currentTimeMillis()}.ndjson.gz")
        val exportedAt = Instant.now()
        val autoUpload = canAutoUpload(diagnostic)
        val reportCollector = if (autoUpload || !diagnostic) ReportCollector(metricsStore) else null

        val summary = runCatching {
            HealthExportEngine(client, onStage).export(
                destination = file,
                previousChangesToken = if (diagnostic) null else syncState.changesToken(),
                previousSuccessfulExport = if (diagnostic) null else syncState.lastSuccessfulExport(),
                diagnostic = diagnostic,
                reportCollector = reportCollector,
                requestedHistoryStartDate = syncState.historyStartDate(),
            )
        }.getOrElse { error ->
            file.delete()
            if (error is CancellationException) throw error
            return SyncOutcome.ExportFailed(error)
        }

        // One write for the whole export; the reports below read the file back.
        reportCollector?.flush()

        val checkpoint = summary.checkpointTime?.let { SyncCheckpoint(summary.checkpointToken, it) }
        if (!autoUpload) return SyncOutcome.ReadyToSave(summary, file, checkpoint)

        return runCatching {
            driveUploader.uploadAfterSync(
                rawFile = file,
                summary = summary,
                exportedAt = exportedAt,
                trigger = trigger,
                sessionWorkouts = reportCollector?.sessionWorkouts().orEmpty(),
                onProgress = onStage,
                launchAuth = launchAuth,
            )
        }.fold(
            onSuccess = { result ->
                file.delete()
                SyncOutcome.Uploaded(
                    summary = summary,
                    uploadedFiles = result.uploadedFiles,
                    checkpointSaved = checkpoint?.let(syncState::persistCheckpoint) ?: true,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                SyncOutcome.UploadFailed(
                    summary = summary,
                    file = file,
                    checkpoint = checkpoint,
                    error = error,
                    needsAuthorization = error is DriveAuthorizationRequired,
                )
            },
        )
    }

    fun persistCheckpoint(checkpoint: SyncCheckpoint): Boolean = syncState.persistCheckpoint(checkpoint)

    private companion object {
        /**
         * The worker and the activity share a process, so one lock is enough to keep two syncs off
         * the same checkpoint, metrics file and Drive folder.
         */
        val syncMutex = Mutex()
    }
}

enum class SyncTrigger(val diagnosticLabel: String) {
    MANUAL("manual"),
    AUTOMATIC("automatic"),
}

sealed interface SyncOutcome {
    /** Export and upload both succeeded; the temporary file is already gone. */
    data class Uploaded(
        val summary: EngineExportResult,
        val uploadedFiles: List<String>,
        val checkpointSaved: Boolean,
    ) : SyncOutcome

    /** Auto-upload is off or diagnostic mode is on; the caller owns [file] and the checkpoint. */
    data class ReadyToSave(
        val summary: EngineExportResult,
        val file: File,
        val checkpoint: SyncCheckpoint?,
    ) : SyncOutcome

    /** Export succeeded but the upload did not; [file] is kept so the caller can still save it. */
    data class UploadFailed(
        val summary: EngineExportResult,
        val file: File,
        val checkpoint: SyncCheckpoint?,
        val error: Throwable,
        val needsAuthorization: Boolean,
    ) : SyncOutcome

    data class ExportFailed(val error: Throwable) : SyncOutcome
}
