package dev.gr0mi4.ohealthinsights

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
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
import androidx.health.connect.client.records.InstantaneousRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.IntervalRecord
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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeSet
import java.util.zip.GZIPOutputStream
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMindfulnessSessionApi::class)
class HealthExportEngine(
    private val client: HealthConnectClient,
    private val onProgress: suspend (String) -> Unit,
) {
    suspend fun export(
        destination: File,
        previousChangesToken: String?,
        previousSuccessfulExport: Instant?,
        diagnostic: Boolean,
    ): EngineExportResult = withContext(Dispatchers.IO) {
        val granted = client.permissionController.getGrantedPermissions()
        val exportedAt = Instant.now()
        val hasHistory = historyPermission in granted
        val historyStart = if (hasHistory) {
            Instant.EPOCH
        } else {
            exportedAt.minus(30, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES)
        }
        val end = exportedAt.plus(1, ChronoUnit.MINUTES)
        val permittedTypes = recordTypes
            .filter { HealthPermission.getReadPermission(it.type) in granted }
            .mapTo(mutableSetOf()) { it.type }

        val plan = if (diagnostic) {
            SyncPlan(
                mode = SyncMode.FULL_DIAGNOSTIC,
                ranges = listOf(TimeWindow(historyStart, end)),
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

        val states = recordTypes.associate { it.name to TypeState() }.toMutableMap()
        val writtenRecordIds = mutableSetOf<String>()
        val writtenWorkoutEnergyIds = mutableSetOf<String>()
        var derivedCount = 0L

        GZIPOutputStream(destination.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.writeJsonLine(
                jsonObjectV3(
                    "kind" to "manifest",
                    "schemaVersion" to 3,
                    "appVersion" to "0.3.0",
                    "syncMode" to plan.mode.wireName,
                    "exportedAt" to exportedAt.toString(),
                    "fullHistoryPermission" to hasHistory,
                    "backgroundReadPermission" to (backgroundPermission in granted),
                    "registeredRecordTypes" to recordTypes.size,
                    "heartRatePolicy" to "workout_or_sleep",
                    "stepsPolicy" to "daily_deduplicated_and_ohealth",
                    "caloriesPolicy" to "daily_and_per_workout",
                    "compression" to "gzip",
                    "changesTokenExpired" to plan.changesTokenExpired,
                ),
            )

            plan.ranges.forEach { range ->
                writer.writeJsonLine(
                    jsonObjectV3(
                        "kind" to "sync_scope",
                        "rangeStart" to range.start.toString(),
                        "rangeEnd" to range.end.toString(),
                        "replaceExistingRange" to (plan.mode != SyncMode.FULL_DIAGNOSTIC),
                    ),
                )
            }
            plan.deletionIds.forEach { recordId ->
                writer.writeJsonLine(
                    jsonObjectV3(
                        "kind" to "deletion",
                        "id" to recordId,
                    ),
                )
            }

            if (plan.mode == SyncMode.INITIAL_COMPACT || plan.mode == SyncMode.FULL_DIAGNOSTIC) {
                writeProbes(writer, granted, historyStart, end)
            }

            if (plan.mode == SyncMode.FULL_DIAGNOSTIC) {
                for ((index, spec) in recordTypes.withIndex()) {
                    onProgress("Raw ${index + 1}/${recordTypes.size}: ${spec.name}")
                    exportRecordType(
                        writer = writer,
                        spec = spec,
                        start = historyStart,
                        end = end,
                        granted = granted,
                        states = states,
                        writtenRecordIds = writtenRecordIds,
                    )
                }
            } else {
                for ((index, range) in plan.ranges.withIndex()) {
                    onProgress("Compact range ${index + 1}/${plan.ranges.size}")
                    derivedCount += exportCompactRange(
                        writer = writer,
                        range = range,
                        granted = granted,
                        states = states,
                        writtenRecordIds = writtenRecordIds,
                        writtenWorkoutEnergyIds = writtenWorkoutEnergyIds,
                    )
                }
            }

            states.forEach { (recordType, state) ->
                val permission = recordTypes
                    .first { it.name == recordType }
                    .let { HealthPermission.getReadPermission(it.type) }
                val status = when {
                    permission !in granted -> "permission_not_granted"
                    state.error == null -> "complete"
                    state.count > 0 -> "partial"
                    else -> "failed"
                }
                state.error?.let { error ->
                    writer.writeJsonLine(
                        jsonObjectV3(
                            "kind" to "type_error",
                            "recordType" to recordType,
                            "errorClass" to error.javaClass.name,
                            "message" to error.message,
                            "recordsPreserved" to state.count,
                        ),
                    )
                }
                writer.writeJsonLine(
                    jsonObjectV3(
                        "kind" to "type_summary",
                        "recordType" to recordType,
                        "status" to status,
                        "count" to state.count,
                    ),
                )
            }

            val rawCount = states.values.sumOf { it.count }
            writer.writeJsonLine(
                jsonObjectV3(
                    "kind" to "export_summary",
                    "rawRecordCount" to rawCount,
                    "derivedRecordCount" to derivedCount,
                    "deletionCount" to plan.deletionIds.size,
                    "nonEmptyTypes" to states.values.count { it.count > 0 },
                    "completedAt" to Instant.now().toString(),
                ),
            )
        }

        EngineExportResult(
            rawRecordCount = states.values.sumOf { it.count },
            derivedRecordCount = derivedCount,
            nonEmptyTypes = states.values.count { it.count > 0 },
            syncMode = plan.mode.wireName,
            checkpointToken = if (diagnostic) null else plan.nextChangesToken,
            checkpointTime = if (diagnostic) null else exportedAt,
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
            val token = client.getChangesToken(ChangesTokenRequest(permittedTypes))
            return SyncPlan(
                mode = SyncMode.INITIAL_COMPACT,
                ranges = listOf(TimeWindow(historyStart, end)),
                nextChangesToken = token,
            )
        }

        val affectedDates = TreeSet<LocalDate>()
        val deletionIds = mutableListOf<String>()
        var token = previousChangesToken
        var tokenExpired = false
        var hasMore = false

        do {
            val response = client.getChanges(token)
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
        } while (hasMore)

        if (tokenExpired) {
            val replacementToken = client.getChangesToken(ChangesTokenRequest(permittedTypes))
            val zone = ZoneId.systemDefault()
            val recoveryStart = (previousSuccessfulExport ?: end.minus(30, ChronoUnit.DAYS))
                .atZone(zone)
                .toLocalDate()
                .minusDays(1)
                .atStartOfDay(zone)
                .toInstant()
            return SyncPlan(
                mode = SyncMode.RECOVERY_COMPACT,
                ranges = listOf(TimeWindow(maxOf(historyStart, recoveryStart), end)),
                nextChangesToken = replacementToken,
                changesTokenExpired = true,
            )
        }

        return SyncPlan(
            mode = SyncMode.INCREMENTAL_COMPACT,
            ranges = datesToRanges(affectedDates, end),
            deletionIds = deletionIds.distinct(),
            nextChangesToken = token,
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
        while (!date.isAfter(endDate)) {
            target += date
            date = date.plusDays(1)
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

    private suspend fun writeProbes(
        writer: BufferedWriter,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ) {
        for ((index, spec) in recordTypes.withIndex()) {
            onProgress("Probe ${index + 1}/${recordTypes.size}: ${spec.name}")
            val permission = HealthPermission.getReadPermission(spec.type)
            if (permission !in granted) {
                writer.writeJsonLine(
                    jsonObjectV3(
                        "kind" to "type_probe",
                        "recordType" to spec.name,
                        "status" to "permission_not_granted",
                        "sampleCount" to 0,
                    ),
                )
                continue
            }
            val probe = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = spec.type,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = 5,
                        ascendingOrder = false,
                    ),
                )
            }
            val response = probe.getOrNull()
            writer.writeJsonLine(
                jsonObjectV3(
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

    private suspend fun exportCompactRange(
        writer: BufferedWriter,
        range: TimeWindow,
        granted: Set<String>,
        states: MutableMap<String, TypeState>,
        writtenRecordIds: MutableSet<String>,
        writtenWorkoutEnergyIds: MutableSet<String>,
    ): Long {
        val workouts = mutableListOf<SessionWindow>()
        val sleeps = mutableListOf<SessionWindow>()
        val expandedStart = runCatching { range.start.minus(1, ChronoUnit.DAYS) }
            .getOrDefault(range.start)

        exportRecordType(
            writer = writer,
            spec = exerciseSpec,
            start = expandedStart,
            end = range.end,
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
            predicate = { record -> record is ExerciseSessionRecord && overlaps(record, range) },
            onRecord = { record ->
                if (record is ExerciseSessionRecord && overlaps(record, range)) {
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
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
            predicate = { record -> record is SleepSessionRecord && overlaps(record, range) },
            onRecord = { record ->
                if (record is SleepSessionRecord && overlaps(record, range)) {
                    sleeps += SessionWindow(
                        id = record.metadata.id,
                        start = record.startTime,
                        end = record.endTime,
                    )
                }
            },
        )

        val sleepWindows = mergeWindows(sleeps.map { TimeWindow(it.start, it.end) })
        val specialTypes = setOf(
            ExerciseSessionRecord::class,
            SleepSessionRecord::class,
            HeartRateRecord::class,
            StepsRecord::class,
            TotalCaloriesBurnedRecord::class,
            OxygenSaturationRecord::class,
            RespiratoryRateRecord::class,
        )
        recordTypes.filter { it.type !in specialTypes }.forEach { spec ->
            exportRecordType(
                writer = writer,
                spec = spec,
                start = range.start,
                end = range.end,
                granted = granted,
                states = states,
                writtenRecordIds = writtenRecordIds,
            )
        }

        listOf(oxygenSpec, respiratorySpec).forEach { spec ->
            exportRecordType(
                writer = writer,
                spec = spec,
                start = range.start,
                end = range.end,
                granted = granted,
                states = states,
                writtenRecordIds = writtenRecordIds,
                predicate = { record -> recordBounds(record)?.let { overlaps(it, sleepWindows) } == true },
            )
        }

        exportRelatedHeartRate(
            writer = writer,
            start = range.start,
            end = range.end,
            granted = granted,
            states = states,
            writtenRecordIds = writtenRecordIds,
            workouts = workouts.sortedBy { it.start },
            sleeps = sleepWindows,
        )

        var derived = exportDailyActivity(writer, range, granted)
        derived += exportWorkoutEnergy(
            writer = writer,
            workouts = workouts,
            granted = granted,
            writtenWorkoutEnergyIds = writtenWorkoutEnergyIds,
        )
        return derived
    }

    private suspend fun exportDailyActivity(
        writer: BufferedWriter,
        range: TimeWindow,
        granted: Set<String>,
    ): Long {
        val canReadSteps = HealthPermission.getReadPermission(StepsRecord::class) in granted
        val canReadCalories = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted
        if (!canReadSteps && !canReadCalories) return 0

        val zone = ZoneId.systemDefault()
        val localStart = LocalDateTime.ofInstant(range.start, zone).toLocalDate().atStartOfDay()
        val localEnd = LocalDateTime.ofInstant(range.end, zone).toLocalDate().plusDays(1).atStartOfDay()
        val metrics = mutableSetOf<AggregateMetric<*>>().apply {
            if (canReadSteps) add(StepsRecord.COUNT_TOTAL)
            if (canReadCalories) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        val totals = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(localStart, localEnd),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).associateBy { it.startTime.toLocalDate() }
        val ohealthSteps = if (canReadSteps) {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(localStart, localEnd),
                    timeRangeSlicer = Period.ofDays(1),
                    dataOriginFilter = setOf(DataOrigin(ohealthPackage)),
                ),
            ).associateBy { it.startTime.toLocalDate() }
        } else {
            emptyMap()
        }

        val dates = (totals.keys + ohealthSteps.keys).toSortedSet()
        dates.forEach { date ->
            val total = totals[date]?.result
            writer.writeJsonLine(
                jsonObjectV3(
                    "kind" to "daily_activity",
                    "date" to date.toString(),
                    "stepsTotalDeduplicated" to total?.get(StepsRecord.COUNT_TOTAL),
                    "stepsOHealth" to ohealthSteps[date]?.result?.get(StepsRecord.COUNT_TOTAL),
                    "totalCaloriesKcal" to total
                        ?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                        ?.inKilocalories,
                    "sourcePackages" to total?.dataOrigins
                        ?.map { it.packageName }
                        ?.sorted()
                        ?.joinToString(","),
                ),
            )
        }
        writer.flush()
        return dates.size.toLong()
    }

    private suspend fun exportWorkoutEnergy(
        writer: BufferedWriter,
        workouts: List<SessionWindow>,
        granted: Set<String>,
        writtenWorkoutEnergyIds: MutableSet<String>,
    ): Long {
        if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) !in granted) return 0
        var count = 0L
        workouts.forEach { workout ->
            val identity = workout.id.ifEmpty { "${workout.start}|${workout.end}" }
            if (!writtenWorkoutEnergyIds.add(identity)) return@forEach
            val result = runCatching {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(workout.start, workout.end),
                    ),
                )
            }
            writer.writeJsonLine(
                jsonObjectV3(
                    "kind" to "workout_energy",
                    "exerciseSessionId" to workout.id,
                    "startTime" to workout.start.toString(),
                    "endTime" to workout.end.toString(),
                    "title" to workout.title,
                    "totalCaloriesKcal" to result.getOrNull()
                        ?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                        ?.inKilocalories,
                    "status" to if (result.isSuccess) "complete" else "failed",
                    "message" to result.exceptionOrNull()?.message,
                ),
            )
            count += 1
        }
        writer.flush()
        return count
    }

    private suspend fun exportRelatedHeartRate(
        writer: BufferedWriter,
        start: Instant,
        end: Instant,
        granted: Set<String>,
        states: MutableMap<String, TypeState>,
        writtenRecordIds: MutableSet<String>,
        workouts: List<SessionWindow>,
        sleeps: List<TimeWindow>,
    ) {
        val permission = HealthPermission.getReadPermission(HeartRateRecord::class)
        if (permission !in granted) return
        val state = states.getValue(heartRateSpec.name)
        val detailedWorkoutIds = mutableSetOf<String>()
        var pageToken: String? = null
        try {
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageSize = 1_000,
                        pageToken = pageToken,
                    ),
                )
                response.records.forEach { record ->
                    val workout = findOverlappingSession(record.startTime, record.endTime, workouts)
                    val inSleep = overlaps(TimeWindow(record.startTime, record.endTime), sleeps)
                    if (workout == null && !inSleep) return@forEach

                    val detailed = record.endTime.isAfter(record.startTime)
                    if (detailed && workout != null) detailedWorkoutIds += workout.id
                    val keep = inSleep || workout == null || detailed || workout.id !in detailedWorkoutIds
                    if (keep && writeRecord(writer, heartRateSpec.name, record, writtenRecordIds)) {
                        state.count += 1
                    }
                }
                writer.flush()
                pageToken = response.pageToken
            } while (pageToken != null)
        } catch (error: Throwable) {
            state.error = state.error ?: error
        }
    }

    private suspend fun exportRecordType(
        writer: BufferedWriter,
        spec: RecordTypeSpec,
        start: Instant,
        end: Instant,
        granted: Set<String>,
        states: MutableMap<String, TypeState>,
        writtenRecordIds: MutableSet<String>,
        predicate: (Record) -> Boolean = { true },
        onRecord: (Record) -> Unit = {},
    ) {
        val permission = HealthPermission.getReadPermission(spec.type)
        if (permission !in granted || !start.isBefore(end)) return
        val state = states.getValue(spec.name)
        var pageToken: String? = null
        try {
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
                    if (!predicate(record)) return@forEach
                    onRecord(record)
                    if (writeRecord(writer, spec.name, record, writtenRecordIds)) {
                        state.count += 1
                    }
                }
                writer.flush()
                pageToken = response.pageToken
            } while (pageToken != null)
        } catch (error: Throwable) {
            state.error = state.error ?: error
        }
    }

    private fun writeRecord(
        writer: BufferedWriter,
        recordType: String,
        record: Record,
        writtenRecordIds: MutableSet<String>,
    ): Boolean {
        val identity = record.metadata.id.ifEmpty {
            "$recordType|${recordBounds(record)}|${record.metadata.dataOrigin.packageName}|${record.hashCode()}"
        }
        if (!writtenRecordIds.add(identity)) return false
        writer.writeJsonLine(
            jsonObjectV3(
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

    private fun recordBounds(record: Record): TimeWindow? = when (record) {
        is IntervalRecord -> TimeWindow(record.startTime, record.endTime)
        is InstantaneousRecord -> TimeWindow(record.time, record.time)
        else -> null
    }

    private fun overlaps(record: IntervalRecord, range: TimeWindow): Boolean =
        record.startTime < range.end && record.endTime > range.start

    private fun overlaps(first: TimeWindow, windows: List<TimeWindow>): Boolean {
        if (windows.isEmpty()) return false
        var low = 0
        var high = windows.lastIndex
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (windows[middle].start <= first.end) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate >= 0 && windows[candidate].end >= first.start
    }

    private fun findOverlappingSession(
        start: Instant,
        end: Instant,
        sessions: List<SessionWindow>,
    ): SessionWindow? {
        if (sessions.isEmpty()) return null
        var low = 0
        var high = sessions.lastIndex
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (sessions[middle].start <= end) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return sessions.getOrNull(candidate)?.takeIf { it.end >= start }
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

    private data class RecordTypeSpec(
        val name: String,
        val type: KClass<out Record>,
    )

    private data class TypeState(
        var count: Long = 0,
        var error: Throwable? = null,
    )

    private data class TimeWindow(
        val start: Instant,
        val end: Instant,
    )

    private data class SessionWindow(
        val id: String,
        val start: Instant,
        val end: Instant,
        val title: String? = null,
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
        private const val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        private const val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        private const val ohealthPackage = "com.heytap.health.international"

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
    }
}

data class EngineExportResult(
    val rawRecordCount: Long,
    val derivedRecordCount: Long,
    val nonEmptyTypes: Int,
    val syncMode: String,
    val checkpointToken: String?,
    val checkpointTime: Instant?,
)

private fun BufferedWriter.writeJsonLine(value: String) {
    write(value)
    newLine()
}

private fun jsonObjectV3(vararg fields: Pair<String, Any?>): String = fields.joinToString(
    prefix = "{",
    postfix = "}",
    separator = ",",
) { (key, value) -> "${jsonStringV3(key)}:${jsonValueV3(value)}" }

private fun jsonValueV3(value: Any?): String = when (value) {
    null -> "null"
    is Boolean, is Number -> value.toString()
    else -> jsonStringV3(value.toString())
}

private fun jsonStringV3(value: String): String = buildString(value.length + 2) {
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
