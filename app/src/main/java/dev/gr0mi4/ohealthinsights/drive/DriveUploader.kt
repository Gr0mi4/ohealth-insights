package dev.gr0mi4.ohealthinsights.drive

import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dev.gr0mi4.ohealthinsights.BuildConfig
import dev.gr0mi4.ohealthinsights.EngineExportResult
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class DriveUploader(
    private val activity: Activity,
    private val settingsStore: DriveSettingsStore,
    private val reportBuilder: ReportBuilder,
) {
    private val authorizationClient = Identity.getAuthorizationClient(activity)

    fun canAutoUpload(settings: DriveSettings, diagnostic: Boolean): Boolean =
        settingsStore.isConfigured() &&
            settings.autoUploadEnabled &&
            !diagnostic &&
            settings.driveAuthorizationGranted

    suspend fun uploadAfterSync(
        rawFile: File,
        summary: EngineExportResult,
        exportedAt: Instant,
        sessionWorkouts: List<WorkoutMetric>,
        onProgress: (String) -> Unit,
        launchAuth: suspend (IntentSenderRequest) -> AuthorizationResult?,
    ): DriveUploadResult {
        val settings = settingsStore.load()
        val token = resolveAccessToken(launchAuth)
            ?: error("Google Drive authorization is required.")
        val names = settings.withResolvedNames(
            NamingContext(
                syncMode = summary.syncMode,
                exportedAt = exportedAt,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        return withContext(Dispatchers.IO) {
            val report = reportBuilder.buildMarkdown(summary, exportedAt, sessionWorkouts)
            val csv = reportBuilder.buildCsv()
            DriveClient(token).uploadSyncBundle(
                settings = settings,
                names = names,
                rawFile = rawFile,
                reportMarkdown = report,
                csvContent = csv,
                onProgress = onProgress,
            )
        }.also { result ->
            settingsStore.updateFolderIds(
                rootFolderId = result.rootFolderId,
                reportsFolderId = result.reportsFolderId,
                archiveFolderId = result.archiveFolderId,
            )
            settingsStore.updateLatestFileIds(
                reportFileId = result.latestReportFileId,
                csvFileId = result.latestCsvFileId,
            )
        }
    }

    suspend fun resolveAccessToken(
        launchAuth: suspend (IntentSenderRequest) -> AuthorizationResult?,
    ): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveAuth.DRIVE_FILE_SCOPE)))
            .build()
        val initial = authorizationClient.authorize(request).await()
        val result = when {
            initial.hasResolution() -> {
                val pending = initial.pendingIntent ?: return null
                launchAuth(IntentSenderRequest.Builder(pending.intentSender).build())
            }
            else -> initial
        } ?: return null
        return result.accessToken
    }
}
