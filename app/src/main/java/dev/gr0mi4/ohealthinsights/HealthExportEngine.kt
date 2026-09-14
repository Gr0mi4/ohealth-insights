package dev.gr0mi4.ohealthinsights

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.gr0mi4.ohealthinsights.drive.ReportCollector
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TreeSet
import java.util.zip.GZIPOutputStream
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads Health Connect data into a gzipped NDJSON file.
 *
 * [onProgress] is invoked from the export thread and must not block: the caller is expected to
 * store the latest message and render it on its own schedule.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
class HealthExportEngine(
    private val client: HealthConnectClient,
    private val onProgress: (String) -> Unit,
) {
    private val slowStages = mutableListOf<StageTiming>()

    suspend fun export(
        destination: File,
        previousChangesToken: String?,
        previousSuccessfulExport: Instant?,
        diagnostic: Boolean,
        reportCollector: ReportCollector? = null,
        requestedHistoryStartDate: LocalDate = defaultHistoryStartDate,
    ): EngineExportResult = withContext(Dispatchers.IO) {
        val granted = criticalHealthCall("Reading Health Connect permissions") {
            client.permissionController.getGrantedPermissions()
        }
        val zone = ZoneId.systemDefault()
        val exportedAt = Instant.now()
        val hasHistory = historyPermission in granted
        val requestedHistoryStart = requestedHistoryStartDate.atStartOfDay(zone).toInstant()
        val historyStart = if (hasHistory) {
            requestedHistoryStart
        } else {
            maxOf(
                requestedHistoryStart,
                exportedAt.minus(30, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES),
            )
        }
        val end = exportedAt.plus(1, ChronoUnit.MINUTES)
        val permittedTypes = recordTypes
            .filter { HealthPermission.getReadPermission(it.type) in granted }
            .mapTo(mutableSetOf()) { it.type }

        val plan = if (diagnostic) {
            SyncPlan(
                mode = SyncMode.FULL_DIAGNOSTIC,
                ranges = chunkRange(TimeWindow(historyStart, end)),
            )
        } else {
            prepareSyncPlan(
                permittedTypes = permittedTypes,
                previousChangesToken = previousChangesToken,
                previousSuccessfulExport = previousSuccessfulExport,
                historyStart = historyStart,
                end = end,
            )
        }

        val states = recordTypes.associate { it.name to TypeState() }
        val writtenRecordIds = BoundedIdSet(maxTrackedRecordIds)
        val writtenWorkoutEnergyIds = mutableSetOf<String>()
        val writtenDailyDates = mutableSetOf<LocalDate>()
        var derivedCount = 0L

        GZIPOutputStream(destination.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.writeJsonLine(
                jsonObject(
                    "kind" to "manifest",
                    "schemaVersion" to 3,
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "syncMode" to plan.mode.wireName,
                    "exportedAt" to exportedAt.toString(),
                    "historyStart" to historyStart.toString(),
                    "timeZone" to zone.id,
                    "fullHistoryPermission" to hasHistory,
                    "backgroundReadPermission" to (backgroundPermission in granted),
                    "registeredRecordTypes" to recordTypes.size,
                    "permittedRecordTypes" to permittedTypes.size,
                    "rangeCount" to plan.ranges.size,
                    "heartRatePolicy" to "workout_or_sleep_windows",
                    "stepsPolicy" to "daily_deduplicated_and_ohealth",
                    "caloriesPolicy" to "ohealth_records_local_day_summary_deduplicated",
                    "compression" to "gzip",
                    "changesTokenExpired" to plan.changesTokenExpired,
                ),
            )

            plan.ranges.forEach { range ->
                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "sync_scope",
                        "rangeStart" to range.start.toString(),
                        "rangeEnd" to range.end.toString(),
                        "replaceExistingRange" to (plan.mode != SyncMode.FULL_DIAGNOSTIC),
                    ),
                )
            }
            plan.deletionIds.forEach { recordId ->
                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "deletion",
                        "id" to recordId,
                    ),
                )
            }

            if (plan.mode == SyncMode.FULL_DIAGNOSTIC) {
                writeProbes(writer, granted, historyStart, end)
            }

            for ((index, range) in plan.ranges.withIndex()) {
                val label = "Range ${index + 1}/${plan.ranges.size} (${rangeLabel(range, zone)})"
                val rangeStartedAt = System.nanoTime()
                val rawBefore = states.values.sumOf { it.count }
                val derivedBefore = derivedCount

                if (plan.mode == SyncMode.FULL_DIAGNOSTIC) {
                    exportRawRange(
                        writer = writer,
                        range = range,
                        label = label,
                        granted = granted,
                        states = states,
                        writtenRecordIds = writtenRecordIds,
                    )
                } else {
                    derivedCount += exportCompactRange(
                        writer = writer,
                        range = range,
                        label = label,
                        granted = granted,
                        states = states,
                        writtenRecordIds = writtenRecordIds,
                        writtenWorkoutEnergyIds = writtenWorkoutEnergyIds,
                        writtenDailyDates = writtenDailyDates,
                        reportCollector = reportCollector,
                    )
                }

                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "range_summary",
                        "rangeStart" to range.start.toString(),
                        "rangeEnd" to range.end.toString(),
                        "rawRecordsWritten" to (states.values.sumOf { it.count } - rawBefore),
                        "derivedRecordsWritten" to (derivedCount - derivedBefore),
                        "durationMillis" to elapsedMillis(rangeStartedAt),
                    ),
                )
                writer.flush()
            }

            onProgress("Finalizing compressed export")
            states.forEach { (recordType, state) ->
                val status = when {
                    permissionByTypeName.getValue(recordType) !in granted -> "permission_not_granted"
                    state.error == null -> "complete"
                    state.count > 0 -> "partial"
                    else -> "failed"
                }
                state.error?.let { error ->
                    writer.writeJsonLine(
                        jsonObject(
                            "kind" to "type_error",
                            "recordType" to recordType,
                            "errorClass" to error.javaClass.name,
                            "message" to error.message,
                            "recordsPreserved" to state.count,
                        ),
                    )
                }
                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "type_summary",
                        "recordType" to recordType,
                        "status" to status,
                        "count" to state.count,
                        "pagesRead" to state.pages,
                        "durationMillis" to state.durationMillis,
                    ),
                )
            }

            slowStages.sortedByDescending { it.durationMillis }.forEach { timing ->
                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "slow_stage",
                        "stage" to timing.stage,
                        "durationMillis" to timing.durationMillis,
                    ),
                )
            }

            writer.writeJsonLine(
                jsonObject(
                    "kind" to "export_summary",
                    "rawRecordCount" to states.values.sumOf { it.count },
                    "derivedRecordCount" to derivedCount,
                    "deletionCount" to plan.deletionIds.size,
                    "nonEmptyTypes" to states.values.count { it.count > 0 },
                    "healthCallCount" to states.values.sumOf { it.pages.toLong() },
                    "slowStageCount" to slowStages.size,
                    "completedAt" to Instant.now().toString(),
                ),
            )
        }

        // A type that dies mid-pagination leaves a hole in this export. Advancing the cursor would
        // hide that hole forever, because an incremental sync only revisits records that change
        // after the cursor, and the skipped ones never will. Holding the checkpoint back instead
        // sends the next run through overlap recovery, which re-reads the range.
        val checkpointHeldBack = !diagnostic && states.values.any { it.error != null }

        EngineExportResult(
            rawRecordCount = states.values.sumOf { it.count },
            derivedRecordCount = derivedCount,
            nonEmptyTypes = states.values.count { it.count > 0 },
            syncMode = plan.mode.wireName,
            rangeCount = plan.ranges.size,
            checkpointToken = if (diagnostic || checkpointHeldBack) null else plan.nextChangesToken,
            checkpointTime = when {
                diagnostic -> null
                checkpointHeldBack -> previousSuccessfulExport
                else -> exportedAt
            },
            warnings = buildList {
                states.forEach { (recordType, state) ->
                    state.error?.let { add("$recordType: ${it.message ?: it.javaClass.simpleName}") }
                }
                if (checkpointHeldBack) {
                    add("Checkpoint held back after a read failure; the next sync re-reads this range.")
                }
                if (!diagnostic && !checkpointHeldBack && plan.nextChangesToken == null) {
                    add("Incremental cursor unavailable; the next sync will use a $fallbackOverlapDays-day overlap.")
                }
                slowStages.maxByOrNull { it.durationMillis }?.let { timing ->
                    add("Slowest stage: ${timing.stage} (${timing.durationMillis / 1_000}s)")
                }
            },
        )
    }

    private suspend fun prepareSyncPlan(
        permittedTypes: Set<KClass<out Record>>,
        previousChangesToken: String?,
        previousSuccessfulExport: Instant?,
        historyStart: Instant,
        end: Instant,
    ): SyncPlan {
        if (previousChangesToken.isNullOrBlank()) {
            val token = requestChangesToken(permittedTypes)
            val recoveryStart = previousSuccessfulExport?.let {
                fallbackRecoveryStart(it, historyStart)
            }
            return SyncPlan(
                mode = if (recoveryStart == null) SyncMode.INITIAL_COMPACT else SyncMode.RECOVERY_COMPACT,
                ranges = chunkRange(TimeWindow(recoveryStart ?: historyStart, end)),
                nextChangesToken = token,
            )
        }

        val affectedDates = TreeSet<LocalDate>()
        val deletionIds = mutableListOf<String>()
        var token: String = previousChangesToken
        var tokenExpired = false
        var hasMore = false
        var changesPage = 1

        try {
            do {
                val response = criticalHealthCall("Reading incremental changes: page $changesPage") {
                    client.getChanges(token)
                }
                if (response.changesTokenExpired) {
                    tokenExpired = true
                    break
                }
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> addAffectedDates(change.record, affectedDates)
                        is DeletionChange -> deletionIds += change.recordId
                        else -> Unit
                    }
                }
                token = response.nextChangesToken
                hasMore = response.hasMore
                changesPage += 1
                if (hasMore && changesPage > maxChangesPages) {
                    onProgress("Warning: change cursor exceeded $maxChangesPages pages; using overlap recovery")
                    return recoveryPlan(
                        permittedTypes = permittedTypes,
                        previousSuccessfulExport = previousSuccessfulExport,
                        historyStart = historyStart,
                        end = end,
                    )
                }
            } while (hasMore)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            onProgress("Warning: change cursor failed (${error.message}); using overlap recovery")
            return SyncPlan(
                mode = SyncMode.RECOVERY_COMPACT,
                ranges = chunkRange(
                    TimeWindow(fallbackRecoveryStart(previousSuccessfulExport, historyStart), end),
                ),
                nextChangesToken = previousChangesToken,
            )
        }

        if (tokenExpired) {
            return recoveryPlan(
                permittedTypes = permittedTypes,
                previousSuccessfulExport = previousSuccessfulExport,
                historyStart = historyStart,
                end = end,
            ).copy(changesTokenExpired = true)
        }

        return SyncPlan(
            mode = SyncMode.INCREMENTAL_COMPACT,
            ranges = datesToRanges(affectedDates, end).flatMap { chunkRange(it) },
            deletionIds = deletionIds.distinct(),
            nextChangesToken = token,
        )
    }

    private suspend fun recoveryPlan(
        permittedTypes: Set<KClass<out Record>>,
        previousSuccessfulExport: Instant?,
        historyStart: Instant,
        end: Instant,
    ): SyncPlan = SyncPlan(
        mode = SyncMode.RECOVERY_COMPACT,
        ranges = chunkRange(
            TimeWindow(fallbackRecoveryStart(previousSuccessfulExport, historyStart), end),
        ),
        nextChangesToken = requestChangesToken(permittedTypes),
    )

    private suspend fun requestChangesToken(
        permittedTypes: Set<KClass<out Record>>,
    ): String? {
        onProgress("Creating incremental cursor (maximum ${changesTokenTimeoutMillis / 1_000} seconds)")
        val token = try {
            withTimeoutOrNull(changesTokenTimeoutMillis) {
                client.getChangesToken(ChangesTokenRequest(permittedTypes))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(logTag, "Changes token request failed", error)
            null
        }
        if (token == null) {
            onProgress("Warning: incremental cursor unavailable; timestamp fallback enabled")
        }
        return token
    }

    private fun fallbackRecoveryStart(
        previousSuccessfulExport: Instant?,
        historyStart: Instant,
    ): Instant {
        val previous = previousSuccessfulExport ?: return historyStart
        val zone = ZoneId.systemDefault()
        return maxOf(
            historyStart,
            previous.atZone(zone)
                .toLocalDate()
                .minusDays(fallbackOverlapDays)
                .atStartOfDay(zone)
                .toInstant(),
        )
    }

    private fun addAffectedDates(record: Record, target: MutableSet<LocalDate>) {
        val zone = ZoneId.systemDefault()
        val bounds = recordBounds(record) ?: return
        var date = bounds.start.atZone(zone).toLocalDate()
        val inclusiveEnd = if (bounds.end.isAfter(bounds.start)) {
            bounds.end.minusNanos(1)
        } else {
            bounds.end
        }
        val endDate = inclusiveEnd.atZone(zone).toLocalDate()
        // A record with a corrupt far-future end time would otherwise walk millions of days.
        var remaining = maxAffectedDaysPerRecord
        while (!date.isAfter(endDate) && remaining > 0) {
            target += date
            date = date.plusDays(1)
            remaining -= 1
        }
    }

    private fun datesToRanges(dates: Set<LocalDate>, exportEnd: Instant): List<TimeWindow> {
        if (dates.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val ranges = mutableListOf<Pair<LocalDate, LocalDate>>()
        for (date in dates.sorted()) {
            val previous = ranges.lastOrNull()
            if (previous != null && date == previous.second.plusDays(1)) {
                ranges[ranges.lastIndex] = previous.first to date
            } else {
                ranges += date to date
            }
        }
        return ranges.map { (first, last) ->
            TimeWindow(
                start = first.atStartOfDay(zone).toInstant(),
                end = minOf(last.plusDays(1).atStartOfDay(zone).toInstant(), exportEnd),
            )
        }.filter { it.start.isBefore(it.end) }
    }

    /**
     * Splits a range into bounded slices so that a multi-month sync reports progress, keeps every
     * Health Connect request small, and never holds a whole history in memory at once.
     */
    private fun chunkRange(range: TimeWindow): List<TimeWindow> {
        if (!range.start.isBefore(range.end)) return emptyList()
        val chunks = mutableListOf<TimeWindow>()
        var start = range.start
        while (start.isBefore(range.end)) {
            val next = minOf(start.plus(rangeChunkDays, ChronoUnit.DAYS), range.end)
            chunks += TimeWindow(start, next)
            start = next
        }
        return chunks
    }

    private suspend fun writeProbes(
        writer: BufferedWriter,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ) {
        for ((index, spec) in recordTypes.withIndex()) {
            val permission = HealthPermission.getReadPermission(spec.type)
            if (permission !in granted) {
                writer.writeJsonLine(
                    jsonObject(
                        "kind" to "type_probe",
                        "recordType" to spec.name,
                        "status" to "permission_not_granted",
                        "sampleCount" to 0,
                    ),
                )
                continue
            }
            val probe = runCatching {
                criticalHealthCall("Probe ${index + 1}/${recordTypes.size}: ${spec.name}") {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = spec.type,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageSize = 5,
                            ascendingOrder = false,
                        ),
                    )
                }
            }
            probe.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val response = probe.getOrNull()
            writer.writeJsonLine(
                jsonObject(
                    "kind" to "type_probe",
                    "recordType" to spec.name,
                    "status" to if (probe.isSuccess) "complete" else "failed",
                    "sampleCount" to (response?.records?.size ?: 0),
                    "sampleSourcePackages" to response?.records
                        ?.mapTo(sortedSetOf()) { it.metadata.dataOrigin.packageName }
                        ?.joinToString(","),
                    "errorClass" to probe.exceptionOrNull()?.javaClass?.name,
                    "message" to probe.exceptionOrNull()?.message,
                ),
            )
            writer.flush()
        }
    }

    private suspend fun exportRawRange(
        writer: BufferedWriter,
        range: TimeWindow,
        label: String,
        granted: Set<String>,
        states: Map<String, TypeState>,
        writtenRecordIds: BoundedIdSet,
    ) {
        for ((index, spec) in recordTypes.withIndex()) {
            exportRecordType(
                writer = writer,
                spec = spec,
                start = range.start,
                end = range.end,
                label = "$label raw ${index + 1}/${recordTypes.size}",
                granted = granted,
                states = states,
                writtenRecordIds = writtenRecordIds,
            )
        }
    }

    private suspend fun exportCompactRange(
        writer: BufferedWriter,
        range: TimeWindow,
        label: String,
        granted: Set<String>,
        states: Map<String, TypeState>,
        writtenRecordIds: BoundedIdSet,
        writtenWorkoutEnergyIds: MutableSet<String>,
        writtenDailyDates: MutableSet<LocalDate>,
        reportCollector: ReportCollector?,
    ): Long {
        val workouts = mutableListOf<SessionWindow>()
        val sleeps = mutableListOf<TimeWindow>()
        // Sessions that started before the range still belong to it, so look one day further back.
        val expandedStart = range.start.minus(1, ChronoUnit.DAYS)

        exportRecordType(
            writer = writer,
            spec = exerciseSpec,
            start = expandedStart,
            end = range.end,
            label = "$label workouts",
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
            predicate = { record -> record is ExerciseSessionRecord && overlaps(record, range) },
            onRecord = { record ->
                if (record is ExerciseSessionRecord) {
                    workouts += SessionWindow(
                        id = record.metadata.id,
                        start = record.startTime,
                        end = record.endTime,
                        title = record.title,
                    )
                }
            },
        )
        exportRecordType(
            writer = writer,
            spec = sleepSpec,
            start = expandedStart,
            end = range.end,
            label = "$label sleep",
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
            predicate = { record -> record is SleepSessionRecord && overlaps(record, range) },
            onRecord = { record ->
                if (record is SleepSessionRecord) {
                    sleeps += TimeWindow(record.startTime, record.endTime)
                    reportCollector?.onSleepSession(
                        sessionId = record.metadata.id,
                        startTime = record.startTime,
                        endTime = record.endTime,
                        actualSleepMinutes = actualSleepMinutes(record),
                    )
                }
            },
        )

        val sleepWindows = mergeWindows(sleeps.mapNotNull { clampTo(it, range) })
        val sessionWindows = mergeWindows(
            (sleeps + workouts.map { TimeWindow(it.start, it.end) }).mapNotNull { clampTo(it, range) },
        )

        recordTypes.filter { it.type !in windowedTypes }.forEach { spec ->
            exportRecordType(
                writer = writer,
                spec = spec,
                start = range.start,
                end = range.end,
                label = "$label records",
                granted = granted,
                states = states,
                writtenRecordIds = writtenRecordIds,
            )
        }

        // Reading these inside session windows only is what keeps a full-history sync finite:
        // continuous heart rate over months is far too large to read and filter afterwards.
        exportRecordTypeInWindows(
            writer = writer,
            spec = heartRateSpec,
            windows = sessionWindows,
            label = "$label heart rate",
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
        )
        exportRecordTypeInWindows(
            writer = writer,
            spec = oxygenSpec,
            windows = sleepWindows,
            label = "$label sleep oxygen",
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
        )
        exportRecordTypeInWindows(
            writer = writer,
            spec = respiratorySpec,
            windows = sleepWindows,
            label = "$label sleep breathing",
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
        )

        // Read once for the whole range: the daily totals and the per-workout figures are then two
        // views of the same deduplicated records, and cannot disagree. Workouts are included in the
        // span because a session may start before the first local day this range covers.
        val zone = ZoneId.systemDefault()
        val (localStart, localEnd) = localDayBounds(range, zone)
        val calorieStart = (workouts.map { it.start } + localStart.atZone(zone).toInstant()).min()
        val calorieEnd = (workouts.map { it.end } + localEnd.atZone(zone).toInstant()).max()
        val calories = readOHealthCalories(
            start = calorieStart,
            end = calorieEnd,
            label = "$label calories",
            canReadActive = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted,
            canReadTotal = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted,
        )

        var derived = exportDailyActivity(
            writer = writer,
            range = range,
            label = label,
            granted = granted,
            calories = calories,
            writtenDailyDates = writtenDailyDates,
            reportCollector = reportCollector,
        )
        derived += exportWorkoutEnergy(
            writer = writer,
            workouts = workouts,
            calories = calories,
            writtenWorkoutEnergyIds = writtenWorkoutEnergyIds,
            reportCollector = reportCollector,
        )
        return derived
    }

    private suspend fun exportDailyActivity(
        writer: BufferedWriter,
        range: TimeWindow,
        label: String,
        granted: Set<String>,
        calories: CalorieSamples,
        writtenDailyDates: MutableSet<LocalDate>,
        reportCollector: ReportCollector?,
    ): Long {
        val canReadSteps = HealthPermission.getReadPermission(StepsRecord::class) in granted
        if (!canReadSteps && calories.samples.isEmpty()) return 0

        val zone = ZoneId.systemDefault()
        val (localStart, localEnd) = localDayBounds(range, zone)
        val windows = dailyAggregationWindows(localStart, localEnd)
        val deduplicatedSteps = if (canReadSteps) {
            windows.flatMapIndexed { index, window ->
                criticalHealthCall("$label deduplicated steps ${index + 1}/${windows.size}") {
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
                            timeRangeSlicer = Period.ofDays(1),
                        ),
                    )
                }
            }.associateBy { it.startTime.toLocalDate() }
        } else {
            emptyMap()
        }
        val ohealthSteps = if (canReadSteps) {
            windows.flatMapIndexed { index, window ->
                criticalHealthCall("$label OHealth steps ${index + 1}/${windows.size}") {
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
                            timeRangeSlicer = Period.ofDays(1),
                            dataOriginFilter = setOf(DataOrigin(ohealthPackage)),
                        ),
                    )
                }
            }.associateBy { it.startTime.toLocalDate() }
        } else {
            emptyMap()
        }
        val caloriesByDate = calories.samples.groupBy { it.localDate }

        var written = 0L
        (deduplicatedSteps.keys + ohealthSteps.keys + caloriesByDate.keys)
            .toSortedSet()
            .forEach { date ->
            if (!writtenDailyDates.add(date)) return@forEach
            val stepsTotal = deduplicatedSteps[date]?.result
            val daySamples = caloriesByDate[date].orEmpty()
            val caloriesOHealthKcal = if (daySamples.isEmpty()) {
                null
            } else {
                daySamples.sumOf { it.kilocalories }
            }
            val calorieCoveredMinutes = daySamples.sumOf { it.minutes }
            writer.writeJsonLine(
                jsonObject(
                    "kind" to "daily_activity",
                    "date" to date.toString(),
                    "stepsTotalDeduplicated" to stepsTotal?.get(StepsRecord.COUNT_TOTAL),
                    "stepsOHealth" to ohealthSteps[date]?.result?.get(StepsRecord.COUNT_TOTAL),
                    "caloriesOHealthKcal" to caloriesOHealthKcal,
                    "caloriesOHealthRecordType" to calories.recordType,
                    "caloriesOHealthRecordCount" to daySamples.size,
                    "caloriesOHealthCoveredMinutes" to calorieCoveredMinutes,
                    "stepsTotalSourcePackages" to stepsTotal?.dataOrigins
                        ?.map { it.packageName }
                        ?.sorted()
                        ?.joinToString(","),
                ),
            )
            reportCollector?.onDailyActivity(
                date = date,
                stepsTotal = stepsTotal?.get(StepsRecord.COUNT_TOTAL),
                stepsOHealth = ohealthSteps[date]?.result?.get(StepsRecord.COUNT_TOTAL),
                caloriesOHealthKcal = caloriesOHealthKcal,
                caloriesCoveredMinutes = calorieCoveredMinutes,
            )
            written += 1
        }
        writer.flush()
        return written
    }

    /**
     * Reads OHealth calorie records directly rather than letting Health Connect aggregate them.
     *
     * Aggregation cannot be used here for two independent reasons. Health Connect fills every
     * minute no record covers with energy derived from basal metabolic rate; on a typical day the
     * watch covers roughly a third of the day, so the filler outweighed the measurement, and the
     * data origin filter does not remove it because derived energy belongs to no origin. OHealth
     * also writes a workout twice, once as per-minute records and once as a single summary record
     * spanning the whole session, so even a plain sum of the records counts those minutes twice.
     *
     * Reading the records and dropping the summaries reproduces the figure the OHealth app shows.
     */
    private suspend fun readOHealthCalories(
        start: Instant,
        end: Instant,
        label: String,
        canReadActive: Boolean,
        canReadTotal: Boolean,
    ): CalorieSamples {
        if (!start.isBefore(end)) return CalorieSamples(emptyList(), null)
        if (canReadActive) {
            val active = readCalorieRecords(
                type = ActiveCaloriesBurnedRecord::class,
                start = start,
                end = end,
                label = "$label (active)",
            ) { record ->
                (record as? ActiveCaloriesBurnedRecord)?.let {
                    CalorieSample(
                        start = it.startTime,
                        end = it.endTime,
                        localDate = localDateOf(it.startTime, it.startZoneOffset),
                        kilocalories = it.energy.inKilocalories,
                    )
                }
            }
            if (active.isNotEmpty()) {
                return CalorieSamples(dropSummaryDuplicates(active), "ActiveCaloriesBurnedRecord")
            }
        }
        if (canReadTotal) {
            val total = readCalorieRecords(
                type = TotalCaloriesBurnedRecord::class,
                start = start,
                end = end,
                label = "$label (total)",
            ) { record ->
                (record as? TotalCaloriesBurnedRecord)?.let {
                    CalorieSample(
                        start = it.startTime,
                        end = it.endTime,
                        localDate = localDateOf(it.startTime, it.startZoneOffset),
                        kilocalories = it.energy.inKilocalories,
                    )
                }
            }
            if (total.isNotEmpty()) {
                return CalorieSamples(dropSummaryDuplicates(total), "TotalCaloriesBurnedRecord")
            }
        }
        return CalorieSamples(emptyList(), null)
    }

    private suspend fun readCalorieRecords(
        type: KClass<out Record>,
        start: Instant,
        end: Instant,
        label: String,
        toSample: (Record) -> CalorieSample?,
    ): List<CalorieSample> {
        val samples = mutableListOf<CalorieSample>()
        var pageToken: String? = null
        var page = 1
        do {
            val response = criticalHealthCall("$label page $page (${samples.size} read)") {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        dataOriginFilter = setOf(DataOrigin(ohealthPackage)),
                        pageSize = readPageSize,
                        pageToken = pageToken,
                    ),
                )
            }
            response.records.forEach { record -> toSample(record)?.let(samples::add) }
            pageToken = response.pageToken
            page += 1
        } while (pageToken != null)
        return samples
    }

    /**
     * Drops any record whose span strictly contains another record's span.
     *
     * OHealth emits one summary record per workout alongside the per-minute records covering the
     * same window; keeping both double-counts the session.
     */
    private fun dropSummaryDuplicates(samples: List<CalorieSample>): List<CalorieSample> {
        if (samples.size < 2) return samples
        val ordered = samples.sortedWith(
            compareBy<CalorieSample> { it.start }.thenByDescending { it.end },
        )
        val kept = ArrayList<CalorieSample>(ordered.size)
        ordered.forEachIndexed { index, candidate ->
            val candidateLength = Duration.between(candidate.start, candidate.end)
            var containsAnother = false
            var probe = index + 1
            while (probe < ordered.size && ordered[probe].start < candidate.end) {
                val other = ordered[probe]
                if (!other.end.isAfter(candidate.end) &&
                    Duration.between(other.start, other.end) < candidateLength
                ) {
                    containsAnother = true
                    break
                }
                probe += 1
            }
            if (!containsAnother) kept += candidate
        }
        return kept
    }

    private fun localDateOf(instant: Instant, offset: ZoneOffset?): LocalDate =
        if (offset != null) {
            instant.atOffset(offset).toLocalDate()
        } else {
            instant.atZone(ZoneId.systemDefault()).toLocalDate()
        }

    /**
     * Minutes actually spent asleep, excluding awake stages inside the session window.
     *
     * The session window is time in bed, which is what the previous report counted and why it read
     * higher than the OHealth app. When Health Connect carries no stages there is no honest way to
     * recover the asleep duration, so this returns null and the caller keeps the window instead.
     */
    private fun actualSleepMinutes(record: SleepSessionRecord): Long? {
        val asleepStages = record.stages.filter { stage ->
            stage.stage == SleepSessionRecord.STAGE_TYPE_SLEEPING ||
                stage.stage == SleepSessionRecord.STAGE_TYPE_LIGHT ||
                stage.stage == SleepSessionRecord.STAGE_TYPE_DEEP ||
                stage.stage == SleepSessionRecord.STAGE_TYPE_REM
        }
        if (asleepStages.isEmpty()) return null
        return asleepStages.sumOf { stage ->
            ChronoUnit.SECONDS.between(stage.startTime, stage.endTime).coerceAtLeast(0)
        } / 60
    }

    private fun localDayBounds(range: TimeWindow, zone: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDateTime.ofInstant(range.start, zone).toLocalDate().atStartOfDay()
        val end = LocalDateTime.ofInstant(range.end.minusNanos(1), zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay()
        return start to end
    }

    private fun dailyAggregationWindows(
        start: LocalDateTime,
        end: LocalDateTime,
    ): List<LocalWindow> {
        val windows = mutableListOf<LocalWindow>()
        var windowStart = start
        while (windowStart < end) {
            val windowEnd = minOf(windowStart.plusDays(dailyAggregationChunkDays), end)
            windows += LocalWindow(windowStart, windowEnd)
            windowStart = windowEnd
        }
        return windows
    }

    private fun exportWorkoutEnergy(
        writer: BufferedWriter,
        workouts: List<SessionWindow>,
        calories: CalorieSamples,
        writtenWorkoutEnergyIds: MutableSet<String>,
        reportCollector: ReportCollector?,
    ): Long {
        if (calories.samples.isEmpty()) return 0
        var count = 0L
        workouts.forEach { workout ->
            val identity = workout.id.ifEmpty { "${workout.start}|${workout.end}" }
            if (!writtenWorkoutEnergyIds.add(identity)) return@forEach
            // Containment is applied here rather than left to the time-range filter, whose handling
            // of records overlapping the session edges would otherwise decide the figure.
            val samples = calories.samples.filter {
                !it.start.isBefore(workout.start) && !it.end.isAfter(workout.end)
            }
            val caloriesOHealthKcal = if (samples.isEmpty()) null else samples.sumOf { it.kilocalories }
            writer.writeJsonLine(
                jsonObject(
                    "kind" to "workout_energy",
                    "exerciseSessionId" to workout.id,
                    "startTime" to workout.start.toString(),
                    "endTime" to workout.end.toString(),
                    "title" to workout.title,
                    "caloriesOHealthKcal" to caloriesOHealthKcal,
                    "caloriesOHealthRecordType" to calories.recordType,
                    "caloriesOHealthRecordCount" to samples.size,
                    "caloriesOHealthCoveredMinutes" to samples.sumOf { it.minutes },
                    "status" to "complete",
                ),
            )
            reportCollector?.onWorkoutEnergy(
                sessionId = workout.id,
                title = workout.title,
                startTime = workout.start,
                endTime = workout.end,
                caloriesKcal = caloriesOHealthKcal,
            )
            count += 1
        }
        writer.flush()
        return count
    }

    private suspend fun exportRecordTypeInWindows(
        writer: BufferedWriter,
        spec: RecordTypeSpec,
        windows: List<TimeWindow>,
        label: String,
        granted: Set<String>,
        states: Map<String, TypeState>,
        writtenRecordIds: BoundedIdSet,
    ) {
        if (HealthPermission.getReadPermission(spec.type) !in granted) return
        windows.forEachIndexed { index, window ->
            // Read slightly before the window and keep whatever overlaps it, so a record that
            // straddles the boundary is not lost to the time filter's containment rules.
            exportRecordType(
                writer = writer,
                spec = spec,
                start = window.start.minus(windowLookbackMinutes, ChronoUnit.MINUTES),
                end = window.end,
                label = "$label ${index + 1}/${windows.size}",
                granted = granted,
                states = states,
                writtenRecordIds = writtenRecordIds,
                predicate = { record -> overlaps(record, window) },
            )
        }
    }

    private suspend fun exportRecordType(
        writer: BufferedWriter,
        spec: RecordTypeSpec,
        start: Instant,
        end: Instant,
        label: String,
        granted: Set<String>,
        states: Map<String, TypeState>,
        writtenRecordIds: BoundedIdSet,
        predicate: (Record) -> Boolean = { true },
        onRecord: (Record) -> Unit = {},
    ) {
        val permission = HealthPermission.getReadPermission(spec.type)
        if (permission !in granted || !start.isBefore(end)) return
        val state = states.getValue(spec.name)
        val startedAt = System.nanoTime()
        var pageToken: String? = null
        var page = 1
        try {
            do {
                val response = criticalHealthCall(
                    "$label · ${spec.name} page $page (${state.count} saved)",
                ) {
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = spec.type,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageSize = readPageSize,
                            pageToken = pageToken,
                        ),
                    )
                }
                state.pages += 1
                response.records.forEach { record ->
                    if (!predicate(record)) return@forEach
                    onRecord(record)
                    if (writeRecord(writer, spec.name, record, writtenRecordIds)) {
                        state.count += 1
                    }
                }
                writer.flush()
                pageToken = response.pageToken
                page += 1
            } while (pageToken != null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            state.error = state.error ?: error
            Log.w(logTag, "${spec.name} failed on page $page", error)
            onProgress("Warning: ${spec.name} failed on page $page: ${error.message}")
        } finally {
            state.durationMillis += elapsedMillis(startedAt)
        }
    }

    private suspend fun <T> criticalHealthCall(
        stage: String,
        block: suspend () -> T,
    ): T {
        onProgress(stage)
        Log.d(logTag, stage)
        val startedAt = System.nanoTime()
        try {
            return withTimeout(healthCallTimeoutMillis) { block() }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                "$stage timed out after ${healthCallTimeoutMillis / 1_000} seconds",
                error,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw IllegalStateException("$stage failed: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            recordStageTiming(stage, elapsedMillis(startedAt))
        }
    }

    private fun recordStageTiming(stage: String, durationMillis: Long) {
        if (durationMillis < slowStageMillis) return
        Log.w(logTag, "Slow stage: $stage took ${durationMillis}ms")
        if (slowStages.size < maxSlowStages) {
            slowStages += StageTiming(stage, durationMillis)
        } else {
            val fastest = slowStages.withIndex().minByOrNull { it.value.durationMillis } ?: return
            if (fastest.value.durationMillis < durationMillis) {
                slowStages[fastest.index] = StageTiming(stage, durationMillis)
            }
        }
    }

    private fun writeRecord(
        writer: BufferedWriter,
        recordType: String,
        record: Record,
        writtenRecordIds: BoundedIdSet,
    ): Boolean {
        val identity = record.metadata.id.ifEmpty {
            "$recordType|${recordBounds(record)}|${record.metadata.dataOrigin.packageName}|${record.hashCode()}"
        }
        if (!writtenRecordIds.add(identity)) return false
        writer.writeJsonLine(
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
        return true
    }

    private fun recordBounds(record: Record): TimeWindow? {
        val accessors = temporalAccessorCache.getOrPut(record.javaClass) {
            val methods = record.javaClass.methods
            TemporalAccessors(
                start = methods.firstOrNull { it.name == "getStartTime" && it.parameterCount == 0 },
                end = methods.firstOrNull { it.name == "getEndTime" && it.parameterCount == 0 },
                time = methods.firstOrNull { it.name == "getTime" && it.parameterCount == 0 },
            )
        }
        val start = accessors.start?.invoke(record) as? Instant
        val end = accessors.end?.invoke(record) as? Instant
        if (start != null && end != null) return TimeWindow(start, end)
        val time = accessors.time?.invoke(record) as? Instant ?: return null
        return TimeWindow(time, time)
    }

    private fun overlaps(record: Record, range: TimeWindow): Boolean {
        val bounds = recordBounds(record) ?: return false
        // Instantaneous records have no duration, so they follow the half-open range rule instead.
        if (bounds.end == bounds.start) {
            return !bounds.start.isBefore(range.start) && bounds.start.isBefore(range.end)
        }
        return bounds.start < range.end && bounds.end > range.start
    }

    private fun clampTo(window: TimeWindow, range: TimeWindow): TimeWindow? {
        val start = maxOf(window.start, range.start)
        val end = minOf(window.end, range.end)
        return if (start.isBefore(end)) TimeWindow(start, end) else null
    }

    private fun mergeWindows(windows: List<TimeWindow>): List<TimeWindow> {
        val sorted = windows.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()
        val merged = mutableListOf(sorted.first())
        sorted.drop(1).forEach { window ->
            val previous = merged.last()
            if (window.start <= previous.end) {
                merged[merged.lastIndex] = TimeWindow(previous.start, maxOf(previous.end, window.end))
            } else {
                merged += window
            }
        }
        return merged
    }

    private fun rangeLabel(range: TimeWindow, zone: ZoneId): String {
        val first = range.start.atZone(zone).toLocalDate()
        val last = range.end.minusNanos(1).atZone(zone).toLocalDate()
        return if (first == last) first.toString() else "$first…$last"
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    /**
     * Deduplicates record ids without growing forever: duplicates only ever come from adjacent or
     * overlapping windows, so evicting the oldest ids is safe.
     */
    private class BoundedIdSet(private val maxSize: Int) {
        private val ids = LinkedHashSet<String>()

        fun add(id: String): Boolean {
            if (!ids.add(id)) return false
            if (ids.size > maxSize) {
                val iterator = ids.iterator()
                iterator.next()
                iterator.remove()
            }
            return true
        }
    }

    private data class RecordTypeSpec(
        val name: String,
        val type: KClass<out Record>,
    )

    private class TypeState(
        var count: Long = 0,
        var pages: Int = 0,
        var durationMillis: Long = 0,
        var error: Throwable? = null,
    )

    private data class TemporalAccessors(
        val start: java.lang.reflect.Method?,
        val end: java.lang.reflect.Method?,
        val time: java.lang.reflect.Method?,
    )

    private data class TimeWindow(
        val start: Instant,
        val end: Instant,
    )

    private data class CalorieSample(
        val start: Instant,
        val end: Instant,
        val localDate: LocalDate,
        val kilocalories: Double,
    ) {
        val minutes: Long
            get() = Duration.between(start, end).toMinutes().coerceAtLeast(1L)
    }

    private data class CalorieSamples(
        val samples: List<CalorieSample>,
        val recordType: String?,
    )

    private data class LocalWindow(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    private data class SessionWindow(
        val id: String,
        val start: Instant,
        val end: Instant,
        val title: String? = null,
    )

    private data class StageTiming(
        val stage: String,
        val durationMillis: Long,
    )

    private data class SyncPlan(
        val mode: SyncMode,
        val ranges: List<TimeWindow>,
        val deletionIds: List<String> = emptyList(),
        val nextChangesToken: String? = null,
        val changesTokenExpired: Boolean = false,
    )

    private enum class SyncMode(val wireName: String) {
        INITIAL_COMPACT("initial_compact"),
        INCREMENTAL_COMPACT("incremental_compact"),
        RECOVERY_COMPACT("recovery_compact"),
        FULL_DIAGNOSTIC("full_diagnostic"),
    }

    companion object {
        const val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        const val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        private const val logTag = "OHealthExport"
        private const val ohealthPackage = "com.heytap.health.international"
        private const val readPageSize = 1_000
        private const val rangeChunkDays = 30L
        private const val dailyAggregationChunkDays = 45L
        private const val windowLookbackMinutes = 60L
        private const val fallbackOverlapDays = 7L
        private const val maxTrackedRecordIds = 250_000
        private const val maxChangesPages = 200
        private const val maxAffectedDaysPerRecord = 400
        private const val maxSlowStages = 40
        private const val slowStageMillis = 5_000L
        private const val changesTokenTimeoutMillis = 15_000L
        private const val healthCallTimeoutMillis = 60_000L
        val defaultHistoryStartDate: LocalDate = LocalDate.of(2025, 4, 1)
        private val temporalAccessorCache = mutableMapOf<Class<*>, TemporalAccessors>()

        private val recordTypes = listOf(
            RecordTypeSpec("ActiveCaloriesBurnedRecord", ActiveCaloriesBurnedRecord::class),
            RecordTypeSpec("BasalBodyTemperatureRecord", BasalBodyTemperatureRecord::class),
            RecordTypeSpec("BasalMetabolicRateRecord", BasalMetabolicRateRecord::class),
            RecordTypeSpec("BloodGlucoseRecord", BloodGlucoseRecord::class),
            RecordTypeSpec("BloodPressureRecord", BloodPressureRecord::class),
            RecordTypeSpec("BodyFatRecord", BodyFatRecord::class),
            RecordTypeSpec("BodyTemperatureRecord", BodyTemperatureRecord::class),
            RecordTypeSpec("BodyWaterMassRecord", BodyWaterMassRecord::class),
            RecordTypeSpec("BoneMassRecord", BoneMassRecord::class),
            RecordTypeSpec("CervicalMucusRecord", CervicalMucusRecord::class),
            RecordTypeSpec("CyclingPedalingCadenceRecord", CyclingPedalingCadenceRecord::class),
            RecordTypeSpec("DistanceRecord", DistanceRecord::class),
            RecordTypeSpec("ElevationGainedRecord", ElevationGainedRecord::class),
            RecordTypeSpec("ExerciseSessionRecord", ExerciseSessionRecord::class),
            RecordTypeSpec("FloorsClimbedRecord", FloorsClimbedRecord::class),
            RecordTypeSpec("HeartRateRecord", HeartRateRecord::class),
            RecordTypeSpec("HeartRateVariabilityRmssdRecord", HeartRateVariabilityRmssdRecord::class),
            RecordTypeSpec("HeightRecord", HeightRecord::class),
            RecordTypeSpec("HydrationRecord", HydrationRecord::class),
            RecordTypeSpec("IntermenstrualBleedingRecord", IntermenstrualBleedingRecord::class),
            RecordTypeSpec("LeanBodyMassRecord", LeanBodyMassRecord::class),
            RecordTypeSpec("MenstruationFlowRecord", MenstruationFlowRecord::class),
            RecordTypeSpec("MenstruationPeriodRecord", MenstruationPeriodRecord::class),
            RecordTypeSpec("MindfulnessSessionRecord", MindfulnessSessionRecord::class),
            RecordTypeSpec("NutritionRecord", NutritionRecord::class),
            RecordTypeSpec("OvulationTestRecord", OvulationTestRecord::class),
            RecordTypeSpec("OxygenSaturationRecord", OxygenSaturationRecord::class),
            RecordTypeSpec("PlannedExerciseSessionRecord", PlannedExerciseSessionRecord::class),
            RecordTypeSpec("PowerRecord", PowerRecord::class),
            RecordTypeSpec("RespiratoryRateRecord", RespiratoryRateRecord::class),
            RecordTypeSpec("RestingHeartRateRecord", RestingHeartRateRecord::class),
            RecordTypeSpec("SexualActivityRecord", SexualActivityRecord::class),
            RecordTypeSpec("SkinTemperatureRecord", SkinTemperatureRecord::class),
            RecordTypeSpec("SleepSessionRecord", SleepSessionRecord::class),
            RecordTypeSpec("SpeedRecord", SpeedRecord::class),
            RecordTypeSpec("StepsCadenceRecord", StepsCadenceRecord::class),
            RecordTypeSpec("StepsRecord", StepsRecord::class),
            RecordTypeSpec("TotalCaloriesBurnedRecord", TotalCaloriesBurnedRecord::class),
            RecordTypeSpec("Vo2MaxRecord", Vo2MaxRecord::class),
            RecordTypeSpec("WeightRecord", WeightRecord::class),
            RecordTypeSpec("WheelchairPushesRecord", WheelchairPushesRecord::class),
        )

        private val exerciseSpec = recordTypes.first { it.type == ExerciseSessionRecord::class }
        private val sleepSpec = recordTypes.first { it.type == SleepSessionRecord::class }
        private val heartRateSpec = recordTypes.first { it.type == HeartRateRecord::class }
        private val oxygenSpec = recordTypes.first { it.type == OxygenSaturationRecord::class }
        private val respiratorySpec = recordTypes.first { it.type == RespiratoryRateRecord::class }

        /** Types the compact sync handles separately: session windows or daily aggregates. */
        private val windowedTypes = setOf(
            ExerciseSessionRecord::class,
            SleepSessionRecord::class,
            HeartRateRecord::class,
            OxygenSaturationRecord::class,
            RespiratoryRateRecord::class,
            StepsRecord::class,
            ActiveCaloriesBurnedRecord::class,
            TotalCaloriesBurnedRecord::class,
        )

        private val permissionByTypeName = recordTypes.associate {
            it.name to HealthPermission.getReadPermission(it.type)
        }

        val recordReadPermissions: Set<String> = permissionByTypeName.values.toSet()

        val recordTypeCount: Int = recordTypes.size
    }
}

data class EngineExportResult(
    val rawRecordCount: Long,
    val derivedRecordCount: Long,
    val nonEmptyTypes: Int,
    val syncMode: String,
    val rangeCount: Int,
    val checkpointToken: String?,
    val checkpointTime: Instant?,
    val warnings: List<String>,
)

private fun BufferedWriter.writeJsonLine(value: String) {
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
