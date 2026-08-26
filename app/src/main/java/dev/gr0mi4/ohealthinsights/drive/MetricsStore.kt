package dev.gr0mi4.ohealthinsights.drive

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * A day of derived metrics.
 *
 * Sessions are stored as maps keyed by Health Connect record id rather than as running totals, so
 * re-exporting a day is a no-op instead of an inflation. Overlap recovery, worker retries and the
 * lookback windows all replay the same sessions, and the reports feed an assistant that reads the
 * numbers literally.
 */
data class DailyMetric(
    val date: LocalDate,
    val stepsTotal: Long? = null,
    val stepsOHealth: Long? = null,
    val totalCaloriesKcal: Double? = null,
    /** Session id to burned kilocalories; the value is null when the session reported no energy. */
    val workouts: Map<String, Double?> = emptyMap(),
    /** Session id to duration in minutes. */
    val sleepSessions: Map<String, Long> = emptyMap(),
    /** Session id to the whole Health Connect sleep-session window in minutes. */
    val timeInBedSessions: Map<String, Long> = emptyMap(),
    val updatedAt: Instant = Instant.now(),
) {
    val workoutCount: Int get() = workouts.size

    val workoutCaloriesKcal: Double?
        get() = workouts.values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    val sleepMinutes: Long?
        get() = sleepSessions.values.takeIf { it.isNotEmpty() }?.sum()

    val timeInBedMinutes: Long?
        get() = timeInBedSessions.values.takeIf { it.isNotEmpty() }?.sum()
}

data class WorkoutMetric(
    val sessionId: String,
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val caloriesKcal: Double?,
    val includedInTotals: Boolean = true,
    val exclusionReason: String? = null,
)

/** One export's worth of changes for a single day, applied in a single pass. */
data class DailyUpdate(
    val stepsTotal: Long? = null,
    val stepsOHealth: Long? = null,
    val totalCaloriesKcal: Double? = null,
    val workouts: Map<String, Double?> = emptyMap(),
    val sleepSessions: Map<String, Long> = emptyMap(),
    val timeInBedSessions: Map<String, Long> = emptyMap(),
)

class MetricsStore(context: android.content.Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /**
     * Applies a whole export at once. Scalars overwrite, sessions merge by id, so replaying an
     * already-seen session changes nothing.
     */
    fun merge(updates: Map<LocalDate, DailyUpdate>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            updates.forEach { (date, update) ->
                val existing = metrics[date] ?: DailyMetric(date = date)
                metrics[date] = existing.copy(
                    stepsTotal = update.stepsTotal ?: existing.stepsTotal,
                    stepsOHealth = update.stepsOHealth ?: existing.stepsOHealth,
                    totalCaloriesKcal = update.totalCaloriesKcal ?: existing.totalCaloriesKcal,
                    workouts = existing.workouts + update.workouts,
                    sleepSessions = existing.sleepSessions + update.sleepSessions,
                    timeInBedSessions = existing.timeInBedSessions + update.timeInBedSessions,
                    updatedAt = Instant.now(),
                )
            }
            prune(metrics)
            saveAll(metrics)
        }
    }

    fun recentDays(days: Int = 14): List<DailyMetric> {
        synchronized(lock) {
            val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
            return loadAll().values
                .filter { !it.date.isBefore(cutoff) }
                .sortedBy { it.date }
        }
    }

    private fun prune(metrics: MutableMap<LocalDate, DailyMetric>) {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS.toLong())
        metrics.keys.filter { it.isBefore(cutoff) }.forEach(metrics::remove)
    }

    private fun loadAll(): Map<LocalDate, DailyMetric> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val sessionSchemaIsCurrent = root.optInt("schemaVersion", 0) >= SCHEMA_VERSION
            val array = root.optJSONArray("days") ?: JSONArray()
            buildMap {
                for (index in 0 until array.length()) {
                    val metric = array.getJSONObject(index).toDailyMetric(sessionSchemaIsCurrent)
                    put(metric.date, metric)
                }
            }
        }.getOrElse { error ->
            // Keep the bad file for inspection rather than letting the next write silently replace
            // months of history with an empty document.
            Log.w(LOG_TAG, "Daily metrics file is unreadable; quarantining it", error)
            file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt"))
            emptyMap()
        }
    }

    /** Writes through a temporary file so a crash mid-write cannot truncate the history. */
    private fun saveAll(metrics: Map<LocalDate, DailyMetric>) {
        val array = JSONArray()
        metrics.values.sortedBy { it.date }.forEach { array.put(it.toJson()) }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("days", array)
            .toString()

        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(file)) {
            temp.delete()
            Log.w(LOG_TAG, "Could not replace the daily metrics file")
        }
    }

    private fun DailyMetric.toJson(): JSONObject = JSONObject().apply {
        put("date", date.toString())
        stepsTotal?.let { put("stepsTotal", it) }
        stepsOHealth?.let { put("stepsOHealth", it) }
        totalCaloriesKcal?.let { put("totalCaloriesKcal", it) }
        put("workouts", JSONObject().apply { workouts.forEach { (id, kcal) -> put(id, kcal ?: JSONObject.NULL) } })
        put("sleepSessions", JSONObject().apply { sleepSessions.forEach { (id, minutes) -> put(id, minutes) } })
        put(
            "timeInBedSessions",
            JSONObject().apply { timeInBedSessions.forEach { (id, minutes) -> put(id, minutes) } },
        )
        put("updatedAt", updatedAt.toString())
    }

    /**
     * Reads both the current and the pre-0.6 layout. The old one stored running totals that double
     * counted every replay, and its session ids are gone, so those two fields start over; steps and
     * calories were always overwritten rather than accumulated and carry across intact.
     */
    private fun JSONObject.toDailyMetric(sessionSchemaIsCurrent: Boolean): DailyMetric = DailyMetric(
        date = LocalDate.parse(getString("date")),
        stepsTotal = optLongOrNull("stepsTotal"),
        stepsOHealth = optLongOrNull("stepsOHealth"),
        totalCaloriesKcal = optDoubleOrNull("totalCaloriesKcal"),
        workouts = if (sessionSchemaIsCurrent) optJSONObject("workouts").toNullableDoubleMap() else emptyMap(),
        sleepSessions = if (sessionSchemaIsCurrent) optJSONObject("sleepSessions").toLongMap() else emptyMap(),
        timeInBedSessions =
            if (sessionSchemaIsCurrent) optJSONObject("timeInBedSessions").toLongMap() else emptyMap(),
        updatedAt = runCatching { Instant.parse(getString("updatedAt")) }.getOrElse { Instant.now() },
    )

    private fun JSONObject?.toNullableDoubleMap(): Map<String, Double?> {
        val source = this ?: return emptyMap()
        return source.keys().asSequence()
            .associateWith { key -> if (source.isNull(key)) null else source.optDouble(key) }
    }

    private fun JSONObject?.toLongMap(): Map<String, Long> {
        val source = this ?: return emptyMap()
        return source.keys().asSequence().associateWith { key -> source.optLong(key) }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    companion object {
        const val RETENTION_DAYS = 90
        private const val SCHEMA_VERSION = 2
        private const val FILE_NAME = "ohealth_daily_metrics.json"
        private const val LOG_TAG = "MetricsStore"
    }
}

/**
 * Buffers an export in memory and writes it once. The previous version touched the metrics file on
 * every daily aggregate and every session, which meant hundreds of full read-parse-write cycles for
 * a single month-long range.
 */
class ReportCollector(
    private val metricsStore: MetricsStore,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    private class DayDraft {
        var stepsTotal: Long? = null
        var stepsOHealth: Long? = null
        var totalCaloriesKcal: Double? = null
        val workouts = mutableMapOf<String, Double?>()
        val sleepSessions = mutableMapOf<String, Long>()
        val timeInBedSessions = mutableMapOf<String, Long>()
    }

    private val drafts = linkedMapOf<LocalDate, DayDraft>()
    private val sessionWorkouts = linkedMapOf<String, WorkoutMetric>()

    fun onDailyActivity(
        date: LocalDate,
        stepsTotal: Long?,
        stepsOHealth: Long?,
        totalCaloriesKcal: Double?,
    ) {
        val draft = drafts.getOrPut(date) { DayDraft() }
        stepsTotal?.let { draft.stepsTotal = it }
        stepsOHealth?.let { draft.stepsOHealth = it }
        totalCaloriesKcal?.let { draft.totalCaloriesKcal = it }
    }

    fun onWorkoutEnergy(
        sessionId: String,
        title: String?,
        startTime: Instant,
        endTime: Instant,
        caloriesKcal: Double?,
    ) {
        sessionWorkouts[sessionId] = WorkoutMetric(
            sessionId = sessionId,
            title = title,
            startTime = startTime,
            endTime = endTime,
            caloriesKcal = caloriesKcal,
        )
    }

    fun onSleepSession(
        sessionId: String,
        startTime: Instant,
        endTime: Instant,
        actualSleepMinutes: Long?,
    ) {
        val timeInBedMinutes = ChronoUnit.MINUTES.between(startTime, endTime).coerceAtLeast(0)
        val date = endTime.atZone(zone).toLocalDate()
        val draft = drafts.getOrPut(date) { DayDraft() }
        actualSleepMinutes?.let { draft.sleepSessions[sessionId] = it }
        draft.timeInBedSessions[sessionId] = timeInBedMinutes
    }

    /** Persists everything buffered so far. Safe to call twice; the second call merges the same ids. */
    fun flush() {
        classifiedWorkouts()
            .filter { it.includedInTotals }
            .forEach { workout ->
                val date = workout.startTime.atZone(zone).toLocalDate()
                drafts.getOrPut(date) { DayDraft() }
                    .workouts[workout.sessionId] = workout.caloriesKcal
            }
        metricsStore.merge(
            drafts.mapValues { (_, draft) ->
                DailyUpdate(
                    stepsTotal = draft.stepsTotal,
                    stepsOHealth = draft.stepsOHealth,
                    totalCaloriesKcal = draft.totalCaloriesKcal,
                    workouts = draft.workouts.toMap(),
                    sleepSessions = draft.sleepSessions.toMap(),
                    timeInBedSessions = draft.timeInBedSessions.toMap(),
                )
            },
        )
    }

    fun sessionWorkouts(): List<WorkoutMetric> = classifiedWorkouts()

    private fun classifiedWorkouts(): List<WorkoutMetric> {
        val all = sessionWorkouts.values.toList()
        val named = all.filterNot { it.isGeneric() }
        return all.map { workout ->
            if (!workout.isGeneric()) return@map workout

            val adjacentToNamed = named.any { other -> workout.gapMinutes(other) <= GENERIC_ADJACENCY_MINUTES }
            val durationMinutes = ChronoUnit.MINUTES.between(workout.startTime, workout.endTime)
                .coerceAtLeast(0)
            val shortAndLowEnergy = durationMinutes < GENERIC_MAX_MINUTES &&
                (workout.caloriesKcal == null || workout.caloriesKcal < GENERIC_MAX_KCAL)
            val reason = when {
                adjacentToNamed -> "generic session adjacent to a named workout"
                shortAndLowEnergy -> "short low-energy generic activity"
                else -> null
            }
            if (reason == null) workout else workout.copy(
                includedInTotals = false,
                exclusionReason = reason,
            )
        }
    }

    private fun WorkoutMetric.isGeneric(): Boolean =
        title.isNullOrBlank() || title.trim().equals("Workout", ignoreCase = true)

    private fun WorkoutMetric.gapMinutes(other: WorkoutMetric): Long = when {
        endTime.isBefore(other.startTime) -> ChronoUnit.MINUTES.between(endTime, other.startTime)
        other.endTime.isBefore(startTime) -> ChronoUnit.MINUTES.between(other.endTime, startTime)
        else -> 0
    }

    companion object {
        private const val GENERIC_ADJACENCY_MINUTES = 30L
        private const val GENERIC_MAX_MINUTES = 45L
        private const val GENERIC_MAX_KCAL = 200.0
    }
}
