package dev.gr0mi4.ohealthinsights

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var permissionsButton: Button
    private lateinit var exportButton: Button
    private lateinit var fullExportButton: Button
    private lateinit var copyLogButton: Button
    private lateinit var progressBar: ProgressBar

    private val debugLog = DebugLog()
    private var healthConnectClient: HealthConnectClient? = null
    private var sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE
    private var pendingExport: File? = null
    private var pendingCheckpoint: PendingCheckpoint? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        debugLog.add("Granted ${granted.size} of ${requestedPermissions.size} requested permissions")
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
                    checkpoint?.let {
                        getSharedPreferences(syncPreferencesName, MODE_PRIVATE)
                            .edit()
                            .putString(changesTokenKey, it.changesToken)
                            .putString(lastSuccessfulExportKey, it.exportedAt.toString())
                            .commit()
                    } ?: true
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
            setOnClickListener { permissionLauncher.launch(requestedPermissions) }
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

        progressBar = ProgressBar(this).apply {
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
            addView(copyLogButton, matchWrap(top = 8))
            addView(progressBar, wrapWrap(top = 12))
            addView(detailsText, matchWrap(top = 16))
        }

        setContentView(ScrollView(this).apply { addView(content) })
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
            val preferences = getSharedPreferences(syncPreferencesName, MODE_PRIVATE)
            val hasCheckpoint = !preferences.getString(lastSuccessfulExportKey, null).isNullOrBlank()
            val hasChangesToken = !preferences.getString(changesTokenKey, null).isNullOrBlank()

            detailsText.text = buildString {
                appendLine("Version ${BuildConfig.VERSION_NAME}")
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
                appendLine()
                append(
                    "Heart rate, oxygen and breathing are read inside workout and sleep windows only. " +
                        "Steps and calories are compacted into daily and workout summaries. " +
                        "Files are gzip-compressed.",
                )
            }
            exportButton.text = if (hasCheckpoint) "Sync new data" else "Create first full sync"
            exportButton.isEnabled = grantedRecordTypes > 0
            fullExportButton.isEnabled = grantedRecordTypes > 0
        }
    }

    private fun startExport(diagnostic: Boolean) {
        val client = healthConnectClient ?: return
        setBusy(true)
        statusText.text = if (diagnostic) "Diagnostic export running" else "Compact sync running"

        lifecycleScope.launch {
            val file = File(cacheDir, "ohealth-insights-${System.currentTimeMillis()}.ndjson.gz")
            val preferences = getSharedPreferences(syncPreferencesName, MODE_PRIVATE)
            val previousToken = if (diagnostic) null else preferences.getString(changesTokenKey, null)
            val previousExport = if (diagnostic) {
                null
            } else {
                preferences.getString(lastSuccessfulExportKey, null)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
            val startedAt = SystemClock.elapsedRealtime()
            val stage = AtomicReference(
                if (diagnostic) "Starting full raw diagnostic export" else "Starting compact sync",
            )

            debugLog.clear()
            debugLog.add("Export started (diagnostic=$diagnostic, version=${BuildConfig.VERSION_NAME})")
            debugLog.add(stage.get())
            renderProgress(stage.get(), startedAt)

            // The engine reports every Health Connect call, so the UI samples the latest stage
            // instead of switching to the main thread thousands of times.
            val timer = launch {
                while (isActive) {
                    delay(progressRefreshMillis)
                    renderProgress(stage.get(), startedAt)
                }
            }
            val result = runCatching {
                HealthExportEngine(client) { message ->
                    stage.set(message)
                    debugLog.add(message, SystemClock.elapsedRealtime() - startedAt)
                }.export(
                    destination = file,
                    previousChangesToken = previousToken,
                    previousSuccessfulExport = previousExport,
                    diagnostic = diagnostic,
                )
            }
            timer.cancel()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }

            result.onSuccess { summary ->
                pendingExport = file
                pendingCheckpoint = summary.checkpointTime?.let {
                    PendingCheckpoint(summary.checkpointToken, it)
                }
                debugLog.add(
                    "Export finished: ${summary.syncMode}, ${summary.rawRecordCount} raw records, " +
                        "${summary.derivedRecordCount} summaries",
                    SystemClock.elapsedRealtime() - startedAt,
                )
                statusText.text = "Export ready to save"
                detailsText.text = buildString {
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
                saveDocumentLauncher.launch(exportFileName(summary.syncMode))
            }.onFailure { error ->
                file.delete()
                debugLog.add(
                    "Export failed at '${stage.get()}': ${error.javaClass.name}: ${error.message}",
                    SystemClock.elapsedRealtime() - startedAt,
                )
                statusText.text = "Export failed"
                detailsText.text = buildString {
                    appendLine("FAILED STAGE")
                    appendLine(stage.get())
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
        }
    }

    private fun renderProgress(stage: String, startedAt: Long) {
        detailsText.text = buildString {
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

    private data class PendingCheckpoint(
        val changesToken: String?,
        val exportedAt: Instant,
    )

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
            const val maxEntries = 500
        }
    }

    companion object {
        private const val healthConnectProviderPackage = "com.google.android.apps.healthdata"
        private const val syncPreferencesName = "ohealth_sync_state"
        private const val changesTokenKey = "changes_token_v1"
        private const val lastSuccessfulExportKey = "last_successful_export_v1"
        private const val progressRefreshMillis = 500L
        private const val visibleStageCount = 6

        private val requestedPermissions = HealthExportEngine.recordReadPermissions +
            HealthExportEngine.historyPermission +
            HealthExportEngine.backgroundPermission
    }
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
