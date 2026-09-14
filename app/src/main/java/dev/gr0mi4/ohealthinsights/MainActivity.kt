package dev.gr0mi4.ohealthinsights

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import dev.gr0mi4.ohealthinsights.drive.DriveSettingsStore
import dev.gr0mi4.ohealthinsights.drive.SettingsActivity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var permissionsButton: Button
    private lateinit var exportButton: Button
    private lateinit var fullExportButton: Button
    private lateinit var copyLogButton: Button
    private lateinit var settingsButton: Button
    private lateinit var historyStartButton: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var driveSettingsStore: DriveSettingsStore
    private lateinit var syncState: SyncStateStore
    private lateinit var syncCoordinator: SyncCoordinator

    private val debugLog = DebugLog()
    private var healthConnectClient: HealthConnectClient? = null
    private var sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE
    private var pendingExport: File? = null
    private var pendingCheckpoint: SyncCheckpoint? = null

    private var displayedProgress = 0
    private var lastRenderedStage: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declining is allowed; the main screen still shows the last sync time. */ }
    private var lastProgressStage: String? = null
    private var lastProgressStageAt = 0L

    private var pendingDriveAuthContinuation: ((AuthorizationResult?) -> Unit)? = null

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val continuation = pendingDriveAuthContinuation
        pendingDriveAuthContinuation = null
        if (result.resultCode != RESULT_OK || result.data == null) {
            continuation?.invoke(null)
            return@registerForActivityResult
        }
        val authResult = runCatching {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data!!)
        }.getOrNull()
        continuation?.invoke(authResult)
    }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        debugLog.add(
            "Granted ${granted.size} of ${SyncStateStore.requestedPermissions.size} requested permissions",
        )
        refreshPermissionState()
    }

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { destination ->
        val source = pendingExport
        val checkpoint = pendingCheckpoint
        pendingExport = null
        pendingCheckpoint = null
        if (destination == null || source == null) {
            debugLog.add("Save cancelled; temporary export discarded")
            appendDetails("Save cancelled. The temporary export was not shared.")
            source?.delete()
            setBusy(false)
            return@registerForActivityResult
        }

        statusText.text = "Saving export file"
        detailsText.text = "Saving compressed export to the selected location…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination, "w")!!.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    source.delete()
                    checkpoint?.let(syncState::persistCheckpoint) ?: true
                }
            }

            result.onSuccess { checkpointSaved ->
                debugLog.add("Export saved; checkpoint persisted: $checkpointSaved")
                detailsText.text = "Export file saved successfully."
                statusText.text = when {
                    checkpoint == null -> "Diagnostic export complete"
                    checkpointSaved -> "Sync checkpoint saved"
                    else -> "Export saved; checkpoint failed"
                }
                if (checkpoint != null && !checkpointSaved) {
                    appendDetails(
                        "The file is safe, but the local checkpoint could not be persisted. " +
                            "The next sync will recover instead of assuming success.",
                    )
                } else {
                    refreshPermissionState()
                }
            }.onFailure {
                debugLog.add("Save failed: ${it.javaClass.name}: ${it.message}")
                appendDetails("Could not save export: ${it.message}")
                statusText.text = "Save failed"
            }
            setBusy(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driveSettingsStore = DriveSettingsStore(this)
        syncState = SyncStateStore(this)
        syncCoordinator = SyncCoordinator(this)
        driveSettingsStore.load().let {
            AutoSyncScheduler.update(this, it.autoSyncEnabled && it.autoUploadEnabled)
        }
        createUi()
        initializeHealthConnect()
    }

    private fun createUi() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        statusText = TextView(this).apply {
            text = "Checking Health Connect…"
            textSize = 22f
        }

        detailsText = TextView(this).apply {
            text = "Compact sync keeps workout and sleep heart rate, daily steps and calories, " +
                "and all other available Health Connect record types."
            textSize = 15f
            setTextIsSelectable(true)
        }

        permissionsButton = Button(this).apply {
            text = "Grant Health Connect access"
            isEnabled = false
            setOnClickListener { permissionLauncher.launch(SyncStateStore.requestedPermissions) }
        }

        exportButton = Button(this).apply {
            text = "Create first full sync"
            isEnabled = false
            setOnClickListener { startExport(diagnostic = false) }
        }

        fullExportButton = Button(this).apply {
            text = "Full raw diagnostic export (slow)"
            isEnabled = false
            setOnClickListener { startExport(diagnostic = true) }
        }

        copyLogButton = Button(this).apply {
            text = "Copy diagnostics log"
            setOnClickListener { copyDiagnostics() }
        }

        settingsButton = Button(this).apply {
            text = "Drive upload settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        historyStartButton = Button(this).apply {
            updateHistoryStartButton()
            setOnClickListener { chooseHistoryStartDate() }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(statusText, matchWrap())
            addView(permissionsButton, matchWrap(top = 16))
            addView(exportButton, matchWrap(top = 8))
            addView(fullExportButton, matchWrap(top = 8))
            addView(historyStartButton, matchWrap(top = 8))
            addView(copyLogButton, matchWrap(top = 8))
            addView(settingsButton, matchWrap(top = 8))
            addView(progressBar, matchWrap(top = 12))
            addView(detailsText, matchWrap(top = 16))
        }

        setContentView(ScrollView(this).apply { addView(content) })

        observeSync()
        requestNotificationPermissionOnce()
    }

    /**
     * Asked here rather than only on the Drive settings screen.
     *
     * Everything the app has to say when it cannot reach the user - a sync blocked for days, a lost
     * Drive grant - goes through a notification, and without this permission those messages are
     * dropped silently, which is the failure they exist to prevent.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (SyncNotifications.canNotify(this)) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun initializeHealthConnect() {
        sdkStatus = HealthConnectClient.getSdkStatus(this)
        debugLog.add("Health Connect SDK status: ${sdkStatusName()}")
        when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> {
                healthConnectClient = HealthConnectClient.getOrCreate(this)
                statusText.text = "Health Connect is available"
                permissionsButton.isEnabled = true
                refreshPermissionState()
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                statusText.text = "Health Connect needs an update"
                detailsText.text = "Update Health Connect, then reopen this app."
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$healthConnectProviderPackage"),
                        ),
                    )
                }
            }

            else -> {
                statusText.text = "Health Connect is unavailable"
                detailsText.text = "This device or Android version does not expose Health Connect."
            }
        }
    }

    private fun refreshPermissionState() {
        val client = healthConnectClient ?: return
        lifecycleScope.launch {
            val granted = runCatching {
                client.permissionController.getGrantedPermissions()
            }.getOrElse {
                debugLog.add("Could not read permissions: ${it.message}")
                appendDetails("Could not read permissions: ${it.message}")
                emptySet()
            }

            val grantedRecordTypes = HealthExportEngine.recordReadPermissions.count { it in granted }
            val history = HealthExportEngine.historyPermission in granted
            val background = HealthExportEngine.backgroundPermission in granted
            val configuredHistoryStart = syncState.historyStartDate()
            val hasCheckpoint = syncState.hasCheckpoint()
            val hasChangesToken = syncState.hasChangesToken()
            val lastSync = syncState.lastCompletedSync()
            val driveSettings = driveSettingsStore.load()

            detailsText.text = buildString {
                appendLine("Version ${BuildConfig.VERSION_NAME}")
                appendLine("Last sync: ${formatLastSync(lastSync)}")
                appendLine("Readable record types: $grantedRecordTypes/${HealthExportEngine.recordTypeCount}")
                appendLine(
                    "Full-history access: " +
                        if (history) "granted" else "not granted; export is limited to the last 30 days",
                )
                appendLine(
                    "Background read access: " +
                        if (background) "granted" else "not granted; keep this screen open during export",
                )
                appendLine(
                    "Sync state: " + when {
                        hasChangesToken -> "incremental changes cursor"
                        hasCheckpoint -> "timestamp recovery with 7-day overlap"
                        else -> "first full sync required"
                    },
                )
                appendLine("History start date: $configuredHistoryStart")
                appendLine(
                    "Drive auto-upload: " + when {
                        !driveSettingsStore.isConfigured() -> "not configured (missing OAuth client ID)"
                        !driveSettings.autoUploadEnabled -> "disabled"
                        !driveSettings.driveAuthorizationGranted -> "enabled; connect Google Drive"
                        else -> "enabled and connected"
                    },
                )
                appendLine(
                    "Daily automatic sync: " + when {
                        !driveSettings.autoSyncEnabled -> "off"
                        !background -> "on, but background read access is missing"
                        !hasCheckpoint -> "on; run the first full sync from this screen"
                        else -> "on"
                    },
                )
                appendLine()
                append(
                    "Heart rate, oxygen and breathing are read inside workout and sleep windows only. " +
                        "Steps and calories are compacted into daily and workout summaries. " +
                        "Files are gzip-compressed.",
                )
            }
            exportButton.text = if (hasCheckpoint) "Sync new data" else "Create first full sync"
            updateHistoryStartButton()
            exportButton.isEnabled = grantedRecordTypes > 0
            fullExportButton.isEnabled = grantedRecordTypes > 0
        }
    }

    /**
     * Hands the export to [SyncService] and only watches it from here.
     *
     * Authorisation is resolved first, while there is still a screen to put a consent dialog on:
     * the service cannot ask for anything once it is running.
     */
    private fun startExport(diagnostic: Boolean) {
        if (healthConnectClient == null || SyncSession.isRunning) return
        displayedProgress = 0
        lastProgressStage = null
        lastProgressStageAt = SystemClock.elapsedRealtime()
        progressBar.progress = 0
        setBusy(true)
        statusText.text = if (diagnostic) "Diagnostic export running" else "Compact sync running"

        lifecycleScope.launch {
            val autoUpload = syncCoordinator.canAutoUpload(diagnostic)
            debugLog.clear()
            debugLog.add(
                "Export started (diagnostic=$diagnostic, autoUpload=$autoUpload, version=${BuildConfig.VERSION_NAME})",
            )

            if (autoUpload) {
                val authorized = runCatching {
                    syncCoordinator.ensureDriveAuthorization { request -> requestDriveAuthorization(request) }
                }.getOrDefault(false)
                if (!authorized) {
                    debugLog.add("Drive authorization was not granted")
                    setBusy(false)
                    statusText.text = "Connect Google Drive before syncing, or turn auto-upload off."
                    return@launch
                }
            }

            SyncService.start(this@MainActivity, diagnostic)
        }
    }

    /** Renders whatever the service is doing, including a run started before this screen existed. */
    private fun observeSync() {
        lifecycleScope.launch {
            SyncSession.state.collect { state ->
                when (state) {
                    is SyncSession.State.Idle -> Unit

                    is SyncSession.State.Running -> {
                        setBusy(true)
                        if (state.stage != lastRenderedStage) {
                            lastRenderedStage = state.stage
                            debugLog.add(
                                state.stage,
                                SystemClock.elapsedRealtime() - state.startedAtElapsedMillis,
                            )
                        }
                        renderProgress(state.stage, state.startedAtElapsedMillis)
                    }

                    is SyncSession.State.Finished -> {
                        lastRenderedStage = null
                        val startedAt = state.startedAtElapsedMillis
                        when (val outcome = state.outcome) {
                            is SyncOutcome.Uploaded -> showUploaded(outcome, startedAt)
                            is SyncOutcome.ReadyToSave -> showReadyToSave(outcome, startedAt)
                            is SyncOutcome.UploadFailed -> showUploadFailed(outcome, startedAt)
                            is SyncOutcome.ExportFailed ->
                                showExportFailed(outcome.error, "export", startedAt)
                        }
                        SyncSession.clearFinished()
                    }
                }
            }
        }
    }

    private fun showUploaded(outcome: SyncOutcome.Uploaded, startedAt: Long) {
        completeProgress()
        debugLog.add(
            "Drive upload complete: ${outcome.uploadedFiles.joinToString()}",
            SystemClock.elapsedRealtime() - startedAt,
        )
        statusText.text =
            if (outcome.checkpointSaved) "Sync uploaded to Drive" else "Uploaded; checkpoint failed"
        detailsText.text = buildString {
            append(buildExportSummary(outcome.summary, startedAt))
            appendLine()
            appendLine("DRIVE UPLOAD")
            outcome.uploadedFiles.forEach { appendLine("• $it") }
            if (!outcome.checkpointSaved) {
                appendLine()
                appendLine("Upload succeeded, but the local checkpoint could not be saved.")
            }
        }
        refreshPermissionState()
        setBusy(false)
    }

    private fun showReadyToSave(outcome: SyncOutcome.ReadyToSave, startedAt: Long) {
        completeProgress()
        debugLog.add(
            "Export finished: ${outcome.summary.syncMode}, ${outcome.summary.rawRecordCount} raw records",
            SystemClock.elapsedRealtime() - startedAt,
        )
        pendingExport = outcome.file
        pendingCheckpoint = outcome.checkpoint
        statusText.text = "Export ready to save"
        detailsText.text = buildExportSummary(outcome.summary, startedAt)
        saveDocumentLauncher.launch(exportFileName(outcome.summary.syncMode))
    }

    private fun showUploadFailed(outcome: SyncOutcome.UploadFailed, startedAt: Long) {
        pendingExport = outcome.file
        pendingCheckpoint = outcome.checkpoint
        debugLog.add(
            "Drive upload failed: ${outcome.error.javaClass.name}: ${outcome.error.message}",
            SystemClock.elapsedRealtime() - startedAt,
        )
        statusText.text = "Drive upload failed — save manually"
        detailsText.text = buildString {
            append(buildExportSummary(outcome.summary, startedAt))
            appendLine()
            appendLine("DRIVE UPLOAD FAILED")
            appendLine(outcome.error.message ?: outcome.error.javaClass.simpleName)
            appendLine()
            appendLine("The export file was kept locally. Choose a save location to keep it.")
            appendLine("Open Drive settings to reconnect, then retry sync.")
        }
        saveDocumentLauncher.launch(exportFileName(outcome.summary.syncMode))
    }

    private fun showExportFailed(error: Throwable, stage: String, startedAt: Long) {
        debugLog.add(
            "Export failed at '$stage': ${error.javaClass.name}: ${error.message}",
            SystemClock.elapsedRealtime() - startedAt,
        )
        statusText.text = "Export failed"
        detailsText.text = buildString {
            appendLine("FAILED STAGE")
            appendLine(stage)
            appendLine()
            appendLine("Elapsed: ${elapsedText(startedAt)}")
            appendLine("Error: ${error.javaClass.name}")
            appendLine(error.message ?: "No error message")
            appendLine()
            appendLine("Use \"Copy diagnostics log\" to share the full stage history.")
            appendLine()
            error.stackTrace.take(12).forEach { frame -> appendLine("at $frame") }
        }
        setBusy(false)
    }

    private fun renderProgress(stage: String, startedAt: Long) {
        val now = SystemClock.elapsedRealtime()
        if (stage != lastProgressStage) {
            lastProgressStage = stage
            lastProgressStageAt = now
        }

        val estimated = estimateProgress(stage)
        if (estimated > displayedProgress) {
            displayedProgress = estimated
            progressBar.setProgress(displayedProgress, true)
        }

        val stageAge = (now - lastProgressStageAt).coerceAtLeast(0L)
        val possibleStallAfter = possibleStallAfterMillis(stage)
        val activity = when {
            stageAge >= possibleStallAfter ->
                "POSSIBLE STALL — this stage has not changed for ${formatElapsed(stageAge)}."
            stageAge >= slowStageNoticeMillis ->
                "Still working on this step for ${formatElapsed(stageAge)}."
            else -> "Current step age: ${formatElapsed(stageAge)}."
        }

        detailsText.text = buildString {
            appendLine("PROGRESS: $displayedProgress%")
            appendLine(activity)
            appendLine()
            appendLine("CURRENT STAGE")
            appendLine(stage)
            appendLine()
            appendLine("Elapsed: ${elapsedText(startedAt)}")
            appendLine("Any single Health Connect request is limited to 60 seconds.")
            val recent = debugLog.snapshot().takeLast(visibleStageCount)
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("RECENT STAGES")
                recent.forEach { appendLine(it) }
            }
        }
    }

    private fun estimateProgress(stage: String): Int {
        rangeProgressRegex.find(stage)?.let { match ->
            val current = match.groupValues[1].toIntOrNull() ?: return displayedProgress
            val total = match.groupValues[2].toIntOrNull() ?: return displayedProgress
            if (total > 0) {
                val withinRange = estimateWithinRange(stage)
                val completed = (current - 1).coerceAtLeast(0).toDouble() + withinRange
                val progress = exportProgressStart + (completed / total) * exportProgressSpan
                return progress.toInt().coerceIn(exportProgressStart, exportProgressEnd)
            }
        }

        probeProgressRegex.find(stage)?.let { match ->
            val current = match.groupValues[1].toIntOrNull() ?: return displayedProgress
            val total = match.groupValues[2].toIntOrNull() ?: return displayedProgress
            if (total > 0) {
                return (4 + (current.toDouble() / total) * 5).toInt().coerceIn(4, 9)
            }
        }

        return when {
            stage.startsWith("Starting") -> 1
            stage.startsWith("Reading Health Connect permissions") -> 2
            stage.startsWith("Creating incremental cursor") -> 4
            stage.startsWith("Reading incremental changes") -> 6
            stage.startsWith("Finalizing compressed export") -> 89
            stage.startsWith("Ensuring Drive folders") -> 91
            stage.startsWith("Uploading raw export") -> 94
            stage.startsWith("Uploading dated report") -> 96
            stage.startsWith("Uploading dated metrics CSV") -> 97
            stage.startsWith("Updating latest report files") -> 99
            else -> displayedProgress
        }
    }

    private fun estimateWithinRange(stage: String): Double {
        rawProgressRegex.find(stage)?.let { match ->
            val current = match.groupValues[1].toIntOrNull() ?: return 0.02
            val total = match.groupValues[2].toIntOrNull() ?: return 0.02
            if (total > 0) return (current.toDouble() / total).coerceIn(0.02, 0.99)
        }

        workoutProgressRegex.find(stage)?.let { match ->
            val current = match.groupValues[1].toIntOrNull() ?: return 0.92
            val total = match.groupValues[2].toIntOrNull() ?: return 0.92
            if (total > 0) return (0.92 + 0.07 * current.toDouble() / total).coerceAtMost(0.99)
        }

        // Ordered to match the stages exportCompactRange runs through, so the bar advances
        // monotonically. Calorie reading is late and heavy: it pages through raw records.
        return when {
            stage.contains("calories (") -> 0.90
            stage.contains("OHealth steps") -> 0.86
            stage.contains("deduplicated steps") -> 0.82
            stage.contains("sleep breathing") -> 0.78
            stage.contains("sleep oxygen") -> 0.72
            stage.contains("heart rate") -> 0.65
            stage.contains(" records") -> 0.30
            stage.contains(" sleep") -> 0.12
            stage.contains(" workouts") -> 0.05
            else -> 0.02
        }
    }

    private fun possibleStallAfterMillis(stage: String): Long = when {
        stage.startsWith("Uploading raw export") -> rawUploadStallMillis
        stage.startsWith("Ensuring Drive folders") ||
            stage.startsWith("Uploading dated") ||
            stage.startsWith("Updating latest") -> driveStageStallMillis
        else -> healthStageStallMillis
    }

    private fun completeProgress() {
        displayedProgress = 100
        progressBar.setProgress(100, true)
    }

    private suspend fun requestDriveAuthorization(
        request: IntentSenderRequest,
    ): AuthorizationResult? = suspendCoroutine { continuation ->
        pendingDriveAuthContinuation = { result -> continuation.resume(result) }
        driveAuthLauncher.launch(request)
    }

    private fun buildExportSummary(summary: EngineExportResult, startedAt: Long): String = buildString {
        appendLine("EXPORT COMPLETE in ${elapsedText(startedAt)}")
        appendLine("Mode: ${summary.syncMode} over ${summary.rangeCount} time ranges")
        appendLine("Raw records: ${summary.rawRecordCount}")
        appendLine("Compact summaries: ${summary.derivedRecordCount}")
        appendLine("Non-empty types: ${summary.nonEmptyTypes}")
        if (summary.warnings.isNotEmpty()) {
            appendLine()
            appendLine("WARNINGS (${summary.warnings.size})")
            summary.warnings.forEach { warning ->
                appendLine("• $warning")
                debugLog.add("Warning: $warning")
            }
        }
    }

    private fun copyDiagnostics() {
        val report = buildString {
            appendLine("OHealth Insights ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Health Connect: ${sdkStatusName()}")
            appendLine()
            appendLine("STAGE LOG")
            val entries = debugLog.snapshot()
            if (entries.isEmpty()) appendLine("(empty)") else entries.forEach { appendLine(it) }
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OHealth Insights diagnostics", report))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun updateHistoryStartButton() {
        if (::historyStartButton.isInitialized) {
            historyStartButton.text = "History starts: ${syncState.historyStartDate()}"
        }
    }

    private fun chooseHistoryStartDate() {
        val current = syncState.historyStartDate()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val selected = LocalDate.of(year, month + 1, day)
                if (selected == current) return@DatePickerDialog
                confirmHistoryStartDate(selected)
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun confirmHistoryStartDate(selected: LocalDate) {
        AlertDialog.Builder(this)
            .setTitle("Start history from $selected?")
            .setMessage(
                "This clears the incremental checkpoint. The next compact sync will be a new full " +
                    "sync from the selected date. Existing exported files are not deleted.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use date") { _, _ ->
                if (syncState.setHistoryStartDate(selected)) {
                    updateHistoryStartButton()
                    refreshPermissionState()
                    Toast.makeText(this, "Next sync will start from $selected", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Could not save history date", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun sdkStatusName(): String = when (sdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update required"
        else -> "unavailable"
    }

    private fun setBusy(busy: Boolean) {
        val ready = healthConnectClient != null
        permissionsButton.isEnabled = !busy && ready
        exportButton.isEnabled = !busy && ready
        fullExportButton.isEnabled = !busy && ready
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun appendDetails(message: String) {
        detailsText.append("\n$message")
    }

    private fun elapsedText(startedAt: Long): String =
        formatElapsed(SystemClock.elapsedRealtime() - startedAt)

    private fun exportFileName(syncMode: String): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "ohealth-insights-v${BuildConfig.VERSION_NAME}-$syncMode-$timestamp.ndjson.gz"
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (top * resources.displayMetrics.density).toInt()
    }

    private fun wrapWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (top * resources.displayMetrics.density).toInt()
    }

    /** Written from the export thread, read from the main thread. */
    private class DebugLog {
        private val entries = ArrayDeque<String>()

        @Synchronized
        fun add(message: String, elapsedMillis: Long? = null) {
            val line = if (elapsedMillis == null) message else "${formatElapsed(elapsedMillis)}  $message"
            if (entries.lastOrNull() == line) return
            entries.addLast(line)
            while (entries.size > maxEntries) entries.removeFirst()
        }

        @Synchronized
        fun snapshot(): List<String> = entries.toList()

        @Synchronized
        fun clear() = entries.clear()

        private companion object {
        private const val UI_PREFS = "ohealth_ui"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
            const val maxEntries = 500
        }
    }

    companion object {
        private const val healthConnectProviderPackage = "com.google.android.apps.healthdata"
        private const val progressRefreshMillis = 500L
        private const val visibleStageCount = 6
        private const val exportProgressStart = 10
        private const val exportProgressEnd = 88
        private const val exportProgressSpan = exportProgressEnd - exportProgressStart
        private const val slowStageNoticeMillis = 10_000L
        private const val healthStageStallMillis = 45_000L
        private const val driveStageStallMillis = 60_000L
        private const val rawUploadStallMillis = 120_000L
        private val rangeProgressRegex = Regex("Range (\\d+)/(\\d+)")
        private val probeProgressRegex = Regex("Probe (\\d+)/(\\d+)")
        private val rawProgressRegex = Regex("raw (\\d+)/(\\d+)")
        private val workoutProgressRegex = Regex("workout calories (\\d+)/(\\d+)")
    }
}

/**
 * Absolute time plus how long ago, because either alone leaves a question: the clock time does not
 * say whether a sync is overdue, and "2 days ago" does not say which day it covered.
 */
private fun formatLastSync(at: Instant?): String {
    if (at == null) return "never"
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(at)
    val minutes = ChronoUnit.MINUTES.between(at, Instant.now())
    val ago = when {
        minutes < 0 -> "clock moved"
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
    return "$stamp ($ago)"
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
