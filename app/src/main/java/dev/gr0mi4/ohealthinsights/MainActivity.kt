package dev.gr0mi4.ohealthinsights

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var permissionsButton: Button
    private lateinit var exportButton: Button
    private lateinit var progressBar: ProgressBar

    private var healthConnectClient: HealthConnectClient? = null
    private var pendingExport: File? = null

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        appendDetails("Granted ${granted.size} of ${requestedPermissions.size} requested permissions.")
        refreshPermissionState()
    }

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { destination ->
        val source = pendingExport
        pendingExport = null
        if (destination == null || source == null) {
            appendDetails("Save cancelled. The temporary export was not shared.")
            source?.delete()
            setBusy(false)
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(destination, "w")!!.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    source.delete()
                }
            }

            result.onSuccess {
                appendDetails("Export saved successfully.")
                statusText.text = "Diagnostic export complete"
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
            text = "This diagnostic build reads locally and exports only to a file you choose."
            textSize = 15f
            setTextIsSelectable(true)
        }

        permissionsButton = Button(this).apply {
            text = "Grant Health Connect access"
            isEnabled = false
            setOnClickListener { permissionLauncher.launch(requestedPermissions) }
        }

        exportButton = Button(this).apply {
            text = "Create diagnostic export"
            isEnabled = false
            setOnClickListener { startExport() }
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
            addView(progressBar, wrapWrap(top = 12))
            addView(detailsText, matchWrap(top = 16))
        }

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun initializeHealthConnect() {
        when (
            HealthConnectClient.getSdkStatus(
                this,
                HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME,
            )
        ) {
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
                            Uri.parse("market://details?id=${HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME}"),
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

            detailsText.text = buildString {
                appendLine("Readable record types: $grantedRecordTypes/${recordTypes.size}")
                appendLine("Full-history access: ${if (history) "granted" else "not granted; export is limited to the last 30 days"}")
                appendLine()
                append("The export keeps source package names and raw record representations so we can identify exactly what OHealth contributes.")
            }
            exportButton.isEnabled = grantedRecordTypes > 0
        }
    }

    private fun startExport() {
        val client = healthConnectClient ?: return
        setBusy(true)
        detailsText.text = "Preparing export…"

        lifecycleScope.launch {
            val file = File(cacheDir, "ohealth-insights-${System.currentTimeMillis()}.ndjson")
            val result = runCatching {
                buildDiagnosticExport(client, file)
            }

            result.onSuccess { summary ->
                pendingExport = file
                statusText.text = "Export ready to save"
                appendDetails("Found ${summary.recordCount} records across ${summary.nonEmptyTypes} non-empty types.")
                saveDocumentLauncher.launch(exportFileName())
            }.onFailure {
                file.delete()
                statusText.text = "Export failed"
                appendDetails(it.stackTraceToString())
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
        val start = if (hasHistory) {
            Instant.EPOCH
        } else {
            exportedAt.minus(30, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES)
        }
        val end = exportedAt.plus(1, ChronoUnit.MINUTES)
        var totalRecords = 0L
        var nonEmptyTypes = 0

        destination.outputStream().buffered().use { output ->
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                writer.writeLine(
                    jsonObject(
                        "kind" to "manifest",
                        "schemaVersion" to 1,
                        "appVersion" to "0.1.0",
                        "exportedAt" to exportedAt.toString(),
                        "rangeStart" to start.toString(),
                        "rangeEnd" to end.toString(),
                        "fullHistoryPermission" to hasHistory,
                        "registeredRecordTypes" to recordTypes.size,
                    ),
                )

                for ((index, spec) in recordTypes.withIndex()) {
                    withContext(Dispatchers.Main) {
                        detailsText.text = "Scanning ${index + 1}/${recordTypes.size}: ${spec.name}"
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
                        continue
                    }

                    val count = runCatching {
                        exportRecordType(client, spec, start, end, writer)
                    }.getOrElse { error ->
                        writer.writeLine(
                            jsonObject(
                                "kind" to "type_error",
                                "recordType" to spec.name,
                                "errorClass" to error.javaClass.name,
                                "message" to error.message,
                            ),
                        )
                        0L
                    }

                    if (count > 0) nonEmptyTypes += 1
                    totalRecords += count
                    writer.writeLine(
                        jsonObject(
                            "kind" to "type_summary",
                            "recordType" to spec.name,
                            "status" to "complete",
                            "count" to count,
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
    ): Long {
        var count = 0L
        var pageToken: String? = null

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
                writer.writeLine(
                    jsonObject(
                        "kind" to "record",
                        "recordType" to spec.name,
                        "id" to record.metadata.id,
                        "sourcePackage" to record.metadata.dataOrigin.packageName,
                        "lastModifiedTime" to record.metadata.lastModifiedTime.toString(),
                        "clientRecordId" to record.metadata.clientRecordId,
                        "clientRecordVersion" to record.metadata.clientRecordVersion,
                        "payload" to record.toString(),
                    ),
                )
                count += 1
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        return count
    }

    private fun setBusy(busy: Boolean) {
        permissionsButton.isEnabled = !busy && healthConnectClient != null
        exportButton.isEnabled = !busy && healthConnectClient != null
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun appendDetails(message: String) {
        detailsText.append("\n$message")
    }

    private fun exportFileName(): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "ohealth-insights-$timestamp.ndjson"
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

    companion object {
        private const val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"

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
            .apply { add(historyPermission) }
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

