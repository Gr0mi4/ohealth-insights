package dev.gr0mi4.ohealthinsights

import android.content.Intent
import android.net.Uri
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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMindfulnessSessionApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var permissionsButton: Button
    private lateinit var exportButton: Button
    private lateinit var fullExportButton: Button
    private lateinit var progressBar: ProgressBar

    private var healthConnectClient: HealthConnectClient? = null
    private var pendingExport: File? = null
    private var pendingCheckpoint: PendingCheckpoint? = null
    private var pendingFileName: String? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        appendDetails("Granted ${granted.size} of ${requestedPermissions.size} requested permissions.")
        refreshPermissionState()
    }

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { destination ->
        val source = pendingExport
        val checkpoint = pendingCheckpoint
        pendingExport = null
        pendingCheckpoint = null
        pendingFileName = null
        if (destination == null || source == null) {
            appendDetails("Save cancelled. The temporary export was not shared.")
            source?.delete()
            setBusy(false)
            return@registerForActivityResult
        }

        statusText.text = "Saving export file"
        detailsText.text = "CURRENT STAGE\nSaving compressed export to the selected location…"
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
                detailsText.text = "Export file saved successfully."
                statusText.text = if (checkpoint == null) {
                    "Diagnostic export complete"
                } else if (checkpointSaved) {
                    "Sync checkpoint saved"
                } else {
                    "Export saved; checkpoint failed"
                }
                if (checkpoint != null && !checkpointSaved) {
                    appendDetails("The file is safe, but the local checkpoint could not be persisted. The next sync will recover instead of assuming success.")
                } else {
                    refreshPermissionState()
                }
            }.onFailure {
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
            text = "Compact sync keeps workout and sleep heart rate, daily steps and calories, and all useful Health Connect record types."
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
            text = "Full raw diagnostic export"
            isEnabled = false
            setOnClickListener { startExport(diagnostic = true) }
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
            addView(progressBar, wrapWrap(top = 12))
            addView(detailsText, matchWrap(top = 16))
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun initializeHealthConnect() {
        when (HealthConnectClient.getSdkStatus(this)) {
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
                appendDetails("Could not read permissions: ${it.message}")
                emptySet()
            }

            val grantedRecordTypes = recordTypes.count {
                HealthPermission.getReadPermission(it.type) in granted
            }
            val history = historyPermission in granted
            val background = backgroundPermission in granted
            val preferences = getSharedPreferences(syncPreferencesName, MODE_PRIVATE)
            val hasCheckpoint = !preferences.getString(lastSuccessfulExportKey, null).isNullOrBlank()
            val hasChangesToken = !preferences.getString(changesTokenKey, null).isNullOrBlank()

            detailsText.text = buildString {
                appendLine("Readable record types: $grantedRecordTypes/${recordTypes.size}")
                appendLine("Full-history access: ${if (history) "granted" else "not granted; export is limited to the last 30 days"}")
                appendLine("Background read access: ${if (background) "granted" else "not granted; keep this screen open during export"}")
                appendLine(
                    "Sync state: ${when {
                        hasChangesToken -> "incremental changes cursor"
                        hasCheckpoint -> "timestamp recovery with 7-day overlap"
                        else -> "first full sync required"
                    }}",
                )
                appendLine()
                append("Heart rate is kept only for workouts and sleep. Steps and calories are compacted into daily and workout summaries. Files are gzip-compressed.")
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
            val file = File(cacheDir, "ohealth-insights-v0.3.3-${System.currentTimeMillis()}.ndjson.gz")
            val preferences = getSharedPreferences(syncPreferencesName, MODE_PRIVATE)
            val previousToken = if (diagnostic) null else preferences.getString(changesTokenKey, null)
            val previousExport = if (diagnostic) {
                null
            } else {
                preferences.getString(lastSuccessfulExportKey, null)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
            val startedAt = SystemClock.elapsedRealtime()
            var currentStage = if (diagnostic) {
                "Starting full raw diagnostic export"
            } else {
                "Starting compact sync"
            }
            val recentStages = mutableListOf(currentStage)

            fun elapsedText(): String {
                val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000
                return "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
            }

            fun renderProgress() {
                detailsText.text = buildString {
                    appendLine("CURRENT STAGE")
                    appendLine(currentStage)
                    appendLine()
                    appendLine("Elapsed: ${elapsedText()}")
                    appendLine("Any single Health Connect request is limited to 60 seconds.")
                    if (recentStages.size > 1) {
                        appendLine()
                        appendLine("RECENT STAGES")
                        recentStages.dropLast(1).takeLast(5).forEach { appendLine("✓ $it") }
                    }
                }
            }

            renderProgress()
            val timer = launch {
                while (isActive) {
                    renderProgress()
                    delay(1_000)
                }
            }
            val result = runCatching {
                HealthExportEngine(client) { message ->
                    withContext(Dispatchers.Main) {
                        currentStage = message
                        if (recentStages.lastOrNull() != message) {
                            recentStages += message
                            while (recentStages.size > 6) recentStages.removeAt(0)
                        }
                        renderProgress()
                    }
                }.export(
                    destination = file,
                    previousChangesToken = previousToken,
                    previousSuccessfulExport = previousExport,
                    diagnostic = diagnostic,
                )
            }
            timer.cancel()

            result.onSuccess { summary ->
                pendingExport = file
                pendingCheckpoint = if (summary.checkpointTime != null) {
                    PendingCheckpoint(summary.checkpointToken, summary.checkpointTime)
                } else {
                    null
                }
                pendingFileName = exportFileName(summary.syncMode)
                statusText.text = "Export ready to save"
                appendDetails(
                    "${summary.syncMode}: ${summary.rawRecordCount} raw records, " +
                        "${summary.derivedRecordCount} compact summaries, " +
                        "${summary.nonEmptyTypes} non-empty types.",
                )
                if (summary.warnings.isNotEmpty()) {
                    appendDetails("Warnings (${summary.warnings.size}):")
                    summary.warnings.forEach { warning -> appendDetails("• $warning") }
                }
                saveDocumentLauncher.launch(pendingFileName!!)
            }.onFailure {
                file.delete()
                statusText.text = "Export failed at: $currentStage"
                detailsText.text = buildString {
                    appendLine("FAILED STAGE")
                    appendLine(currentStage)
                    appendLine()
                    appendLine("Elapsed: ${elapsedText()}")
                    appendLine("Error: ${it.javaClass.name}")
                    appendLine(it.message ?: "No error message")
                    appendLine()
                    appendLine("Send a screenshot of this block.")
                    appendLine()
                    it.stackTrace.take(12).forEach { frame -> appendLine("at $frame") }
                }
                setBusy(false)
            }
        }
    }

    private suspend fun buildDiagnosticExport(
        client: HealthConnectClient,
        destination: File,
    ): ExportSummary = withContext(Dispatchers.IO) {
        val granted = client.permissionController.getGrantedPermissions()
        val exportedAt = Instant.now()
        val hasHistory = historyPermission in granted
        val knownHistoryStart = LocalDate.of(2025, 4, 1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val start = if (hasHistory) {
            knownHistoryStart
        } else {
            maxOf(
                knownHistoryStart,
                exportedAt.minus(30, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES),
            )
        }
        val end = exportedAt.plus(1, ChronoUnit.MINUTES)
        var totalRecords = 0L
        var nonEmptyTypes = 0

        destination.outputStream().buffered().use { output ->
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                writer.writeLine(
                    jsonObject(
                        "kind" to "manifest",
                        "schemaVersion" to 2,
                        "appVersion" to "0.2.0",
                        "exportedAt" to exportedAt.toString(),
                        "rangeStart" to start.toString(),
                        "rangeEnd" to end.toString(),
                        "fullHistoryPermission" to hasHistory,
                        "backgroundReadPermission" to (backgroundPermission in granted),
                        "registeredRecordTypes" to recordTypes.size,
                        "heartRatePolicy" to "workout_intervals_only",
                    ),
                )

                for ((index, spec) in recordTypes.withIndex()) {
                    withContext(Dispatchers.Main) {
                        detailsText.text = "Probing ${index + 1}/${recordTypes.size}: ${spec.name}"
                    }

                    val permission = HealthPermission.getReadPermission(spec.type)
                    if (permission !in granted) {
                        writer.writeLine(
                            jsonObject(
                                "kind" to "type_probe",
                                "recordType" to spec.name,
                                "status" to "permission_not_granted",
                                "sampleCount" to 0,
                            ),
                        )
                        continue
                    }

                    val probe = probeRecordType(client, spec, start, end)
                    writer.writeLine(
                        jsonObject(
                            "kind" to "type_probe",
                            "recordType" to spec.name,
                            "status" to if (probe.error == null) "complete" else "failed",
                            "sampleCount" to probe.sampleCount,
                            "sampleSourcePackages" to probe.sourcePackages.joinToString(","),
                            "errorClass" to probe.error?.javaClass?.name,
                            "message" to probe.error?.message,
                        ),
                    )
                    writer.flush()
                }

                val workoutWindows = mutableListOf<TimeWindow>()
                val exerciseSpec = recordTypes.first { it.type == ExerciseSessionRecord::class }
                val heartRateSpec = recordTypes.first { it.type == HeartRateRecord::class }
                val exportOrder = listOf(exerciseSpec) +
                    recordTypes.filter { it != exerciseSpec && it != heartRateSpec } +
                    heartRateSpec

                for ((index, spec) in exportOrder.withIndex()) {
                    withContext(Dispatchers.Main) {
                        detailsText.text = "Exporting ${index + 1}/${exportOrder.size}: ${spec.name}"
                    }

                    val permission = HealthPermission.getReadPermission(spec.type)
                    if (permission !in granted) {
                        writer.writeLine(
                            jsonObject(
                                "kind" to "type_summary",
                                "recordType" to spec.name,
                                "status" to "permission_not_granted",
                                "count" to 0,
                            ),
                        )
                        writer.flush()
                        continue
                    }

                    val mergedWorkoutWindows = if (spec == heartRateSpec) {
                        mergeTimeWindows(workoutWindows)
                    } else {
                        emptyList()
                    }

                    if (spec == heartRateSpec && mergedWorkoutWindows.isEmpty()) {
                        writer.writeLine(
                            jsonObject(
                                "kind" to "type_summary",
                                "recordType" to spec.name,
                                "status" to "no_workout_intervals",
                                "count" to 0,
                                "heartRatePolicy" to "workout_intervals_only",
                            ),
                        )
                        writer.flush()
                        continue
                    }

                    val result = when (spec) {
                        exerciseSpec -> exportRecordType(client, spec, start, end, writer) { record ->
                            if (record is ExerciseSessionRecord && record.endTime.isAfter(record.startTime)) {
                                workoutWindows += TimeWindow(record.startTime, record.endTime)
                            }
                        }

                        heartRateSpec -> exportWorkoutHeartRate(
                            client = client,
                            workoutWindows = mergedWorkoutWindows,
                            writer = writer,
                        )

                        else -> exportRecordType(client, spec, start, end, writer)
                    }

                    result.error?.let { error ->
                        writer.writeLine(
                            jsonObject(
                                "kind" to "type_error",
                                "recordType" to spec.name,
                                "errorClass" to error.javaClass.name,
                                "message" to error.message,
                                "recordsPreserved" to result.count,
                            ),
                        )
                    }

                    if (result.count > 0) nonEmptyTypes += 1
                    totalRecords += result.count
                    writer.writeLine(
                        jsonObject(
                            "kind" to "type_summary",
                            "recordType" to spec.name,
                            "status" to when {
                                result.error == null -> "complete"
                                result.count > 0 -> "partial"
                                else -> "failed"
                            },
                            "count" to result.count,
                            "workoutIntervals" to if (spec == heartRateSpec) mergedWorkoutWindows.size else null,
                            "heartRatePolicy" to if (spec == heartRateSpec) "workout_intervals_only" else null,
                        ),
                    )
                    writer.flush()
                }

                writer.writeLine(
                    jsonObject(
                        "kind" to "export_summary",
                        "recordCount" to totalRecords,
                        "nonEmptyTypes" to nonEmptyTypes,
                        "completedAt" to Instant.now().toString(),
                    ),
                )
            }
        }

        ExportSummary(totalRecords, nonEmptyTypes)
    }

    private suspend fun exportRecordType(
        client: HealthConnectClient,
        spec: RecordType,
        start: Instant,
        end: Instant,
        writer: BufferedWriter,
        onRecord: (Record) -> Unit = {},
    ): TypeExportResult {
        var count = 0L
        var pageToken: String? = null

        return try {
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = spec.type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = 1_000,
                        pageToken = pageToken,
                    ),
                )

                response.records.forEach { record ->
                    writeRecord(writer, spec.name, record)
                    onRecord(record)
                    count += 1
                }
                writer.flush()
                pageToken = response.pageToken
            } while (pageToken != null)
            TypeExportResult(count = count)
        } catch (error: Throwable) {
            TypeExportResult(count = count, error = error)
        }
    }

    private suspend fun probeRecordType(
        client: HealthConnectClient,
        spec: RecordType,
        start: Instant,
        end: Instant,
    ): TypeProbeResult = try {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = spec.type,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                pageSize = 5,
                ascendingOrder = false,
            ),
        )
        TypeProbeResult(
            sampleCount = response.records.size,
            sourcePackages = response.records
                .mapTo(sortedSetOf()) { it.metadata.dataOrigin.packageName },
        )
    } catch (error: Throwable) {
        TypeProbeResult(error = error)
    }

    private suspend fun exportWorkoutHeartRate(
        client: HealthConnectClient,
        workoutWindows: List<TimeWindow>,
        writer: BufferedWriter,
    ): TypeExportResult {
        var count = 0L
        val exportedIds = mutableSetOf<String>()

        return try {
            workoutWindows.forEach { workoutWindow ->
                var pageToken: String? = null
                do {
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(workoutWindow.start, workoutWindow.end),
                            pageSize = 1_000,
                            pageToken = pageToken,
                        ),
                    )

                    response.records.forEach { record ->
                        val identity = record.metadata.id.ifEmpty {
                            "${record.startTime}|${record.endTime}|${record.metadata.dataOrigin.packageName}|${record.hashCode()}"
                        }
                        if (exportedIds.add(identity)) {
                            writeRecord(writer, "HeartRateRecord", record)
                            count += 1
                        }
                    }
                    writer.flush()
                    pageToken = response.pageToken
                } while (pageToken != null)
            }
            TypeExportResult(count = count)
        } catch (error: Throwable) {
            TypeExportResult(count = count, error = error)
        }
    }

    private fun writeRecord(writer: BufferedWriter, recordType: String, record: Record) {
        writer.writeLine(
            jsonObject(
                "kind" to "record",
                "recordType" to recordType,
                "id" to record.metadata.id,
                "sourcePackage" to record.metadata.dataOrigin.packageName,
                "lastModifiedTime" to record.metadata.lastModifiedTime.toString(),
                "clientRecordId" to record.metadata.clientRecordId,
                "clientRecordVersion" to record.metadata.clientRecordVersion,
                "payload" to record.toString(),
            ),
        )
    }

    private fun mergeTimeWindows(windows: List<TimeWindow>): List<TimeWindow> {
        val sorted = windows.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { workoutWindow ->
            val previous = merged.last()
            if (!workoutWindow.start.isAfter(previous.end)) {
                merged[merged.lastIndex] = TimeWindow(
                    start = previous.start,
                    end = maxOf(previous.end, workoutWindow.end),
                )
            } else {
                merged += workoutWindow
            }
        }
        return merged
    }

    private fun setBusy(busy: Boolean) {
        permissionsButton.isEnabled = !busy && healthConnectClient != null
        exportButton.isEnabled = !busy && healthConnectClient != null
        fullExportButton.isEnabled = !busy && healthConnectClient != null
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

    private fun exportFileName(syncMode: String): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "ohealth-insights-v0.3.3-$syncMode-$timestamp.ndjson.gz"
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

    private data class RecordType(
        val name: String,
        val type: KClass<out Record>,
    )

    private data class ExportSummary(
        val recordCount: Long,
        val nonEmptyTypes: Int,
    )

    private data class PendingCheckpoint(
        val changesToken: String?,
        val exportedAt: Instant,
    )

    private data class TypeExportResult(
        val count: Long,
        val error: Throwable? = null,
    )

    private data class TypeProbeResult(
        val sampleCount: Int = 0,
        val sourcePackages: Set<String> = emptySet(),
        val error: Throwable? = null,
    )

    private data class TimeWindow(
        val start: Instant,
        val end: Instant,
    )

    companion object {
        private const val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        private const val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val healthConnectProviderPackage = "com.google.android.apps.healthdata"
        private const val syncPreferencesName = "ohealth_sync_state"
        private const val changesTokenKey = "changes_token_v1"
        private const val lastSuccessfulExportKey = "last_successful_export_v1"

        private val recordTypes = listOf(
            RecordType("ActiveCaloriesBurnedRecord", ActiveCaloriesBurnedRecord::class),
            RecordType("BasalBodyTemperatureRecord", BasalBodyTemperatureRecord::class),
            RecordType("BasalMetabolicRateRecord", BasalMetabolicRateRecord::class),
            RecordType("BloodGlucoseRecord", BloodGlucoseRecord::class),
            RecordType("BloodPressureRecord", BloodPressureRecord::class),
            RecordType("BodyFatRecord", BodyFatRecord::class),
            RecordType("BodyTemperatureRecord", BodyTemperatureRecord::class),
            RecordType("BodyWaterMassRecord", BodyWaterMassRecord::class),
            RecordType("BoneMassRecord", BoneMassRecord::class),
            RecordType("CervicalMucusRecord", CervicalMucusRecord::class),
            RecordType("CyclingPedalingCadenceRecord", CyclingPedalingCadenceRecord::class),
            RecordType("DistanceRecord", DistanceRecord::class),
            RecordType("ElevationGainedRecord", ElevationGainedRecord::class),
            RecordType("ExerciseSessionRecord", ExerciseSessionRecord::class),
            RecordType("FloorsClimbedRecord", FloorsClimbedRecord::class),
            RecordType("HeartRateRecord", HeartRateRecord::class),
            RecordType("HeartRateVariabilityRmssdRecord", HeartRateVariabilityRmssdRecord::class),
            RecordType("HeightRecord", HeightRecord::class),
            RecordType("HydrationRecord", HydrationRecord::class),
            RecordType("IntermenstrualBleedingRecord", IntermenstrualBleedingRecord::class),
            RecordType("LeanBodyMassRecord", LeanBodyMassRecord::class),
            RecordType("MenstruationFlowRecord", MenstruationFlowRecord::class),
            RecordType("MenstruationPeriodRecord", MenstruationPeriodRecord::class),
            RecordType("MindfulnessSessionRecord", MindfulnessSessionRecord::class),
            RecordType("NutritionRecord", NutritionRecord::class),
            RecordType("OvulationTestRecord", OvulationTestRecord::class),
            RecordType("OxygenSaturationRecord", OxygenSaturationRecord::class),
            RecordType("PlannedExerciseSessionRecord", PlannedExerciseSessionRecord::class),
            RecordType("PowerRecord", PowerRecord::class),
            RecordType("RespiratoryRateRecord", RespiratoryRateRecord::class),
            RecordType("RestingHeartRateRecord", RestingHeartRateRecord::class),
            RecordType("SexualActivityRecord", SexualActivityRecord::class),
            RecordType("SkinTemperatureRecord", SkinTemperatureRecord::class),
            RecordType("SleepSessionRecord", SleepSessionRecord::class),
            RecordType("SpeedRecord", SpeedRecord::class),
            RecordType("StepsCadenceRecord", StepsCadenceRecord::class),
            RecordType("StepsRecord", StepsRecord::class),
            RecordType("TotalCaloriesBurnedRecord", TotalCaloriesBurnedRecord::class),
            RecordType("Vo2MaxRecord", Vo2MaxRecord::class),
            RecordType("WeightRecord", WeightRecord::class),
            RecordType("WheelchairPushesRecord", WheelchairPushesRecord::class),
        )

        private val requestedPermissions = recordTypes
            .mapTo(mutableSetOf()) { HealthPermission.getReadPermission(it.type) }
            .apply {
                add(historyPermission)
                add(backgroundPermission)
            }
    }
}

private fun BufferedWriter.writeLine(value: String) {
    write(value)
    newLine()
}

private fun jsonObject(vararg fields: Pair<String, Any?>): String = fields.joinToString(
    prefix = "{",
    postfix = "}",
    separator = ",",
) { (key, value) -> "${jsonString(key)}:${jsonValue(value)}" }

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    else -> jsonString(value.toString())
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
