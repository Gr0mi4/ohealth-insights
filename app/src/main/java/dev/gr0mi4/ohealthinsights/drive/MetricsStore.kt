package dev.gr0mi4.ohealthinsights.drive

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workouts and sleep are stored keyed by session rather than accumulated.
 *
 * A range can legitimately be exported more than once - a retry, an overlap recovery, or a session
 * that straddles a chunk boundary - and counters that added on every pass grew without bound, so
 * the report and CSV handed to ChatGPT claimed workouts and sleep the user never had.
 */
data class DailyMetric(
    val date: LocalDate,
    val stepsTotal: Long? = null,
    val stepsOHealth: Long? = null,
    val caloriesOHealthKcal: Double? = null,
    val caloriesCoveredMinutes: Long? = null,
    val workoutCalories: Map<String, Double?> = emptyMap(),
    val timeInBedMinutes: Map<String, Long> = emptyMap(),
    val sleepAwakenings: Map<String, Int> = emptyMap(),
    val updatedAt: Instant = Instant.now(),
) {
    val workoutCount: Int
        get() = workoutCalories.size

    val workoutCaloriesKcal: Double?
        get() = workoutCalories.values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /**
     * Time in bed, not time asleep. OHealth subtracts the awake segments it marks in its own app,
     * which Health Connect never receives, so this reads a little higher than the watch shows.
     */
    val sleepMinutes: Long?
        get() = timeInBedMinutes.values.takeIf { it.isNotEmpty() }?.sum()

    val awakenings: Int?
        get() = sleepAwakenings.values.takeIf { it.isNotEmpty() }?.sum()
}

data class WorkoutMetric(
    val sessionId: String,
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val caloriesKcal: Double?,
)

class MetricsStore(context: android.content.Context) {
    private val file = java.io.File(context.filesDir, "ohealth_daily_metrics.json")
    private val lock = Any()

    fun upsertDaily(metric: DailyMetric) {
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            val existing = metrics[metric.date]
            metrics[metric.date] = metric.copy(
                workoutCalories = existing?.workoutCalories ?: emptyMap(),
                timeInBedMinutes = existing?.timeInBedMinutes ?: emptyMap(),
                sleepAwakenings = existing?.sleepAwakenings ?: emptyMap(),
                stepsTotal = metric.stepsTotal ?: existing?.stepsTotal,
                stepsOHealth = metric.stepsOHealth ?: existing?.stepsOHealth,
                caloriesOHealthKcal = metric.caloriesOHealthKcal ?: existing?.caloriesOHealthKcal,
                caloriesCoveredMinutes = metric.caloriesCoveredMinutes ?: existing?.caloriesCoveredMinutes,
                updatedAt = metric.updatedAt,
            )
            prune(metrics)
            saveAll(metrics)
        }
    }

    fun addWorkout(date: LocalDate, workout: WorkoutMetric) {
        val key = workout.sessionId.ifEmpty { "${workout.startTime}|${workout.endTime}" }
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            val existing = metrics[date] ?: DailyMetric(date = date)
            metrics[date] = existing.copy(
                workoutCalories = existing.workoutCalories + (key to workout.caloriesKcal),
                updatedAt = Instant.now(),
            )
            prune(metrics)
            saveAll(metrics)
        }
    }

    fun addSleepSession(date: LocalDate, key: String, minutes: Long, awakenings: Int?) {
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            val existing = metrics[date] ?: DailyMetric(date = date)
            metrics[date] = existing.copy(
                timeInBedMinutes = existing.timeInBedMinutes + (key to minutes),
                sleepAwakenings = if (awakenings == null) {
                    existing.sleepAwakenings
                } else {
                    existing.sleepAwakenings + (key to awakenings)
                },
                updatedAt = Instant.now(),
            )
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

    fun allMetrics(): List<DailyMetric> = synchronized(lock) {
        loadAll().values.sortedBy { it.date }
    }

    private fun prune(metrics: MutableMap<LocalDate, DailyMetric>) {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS.toLong())
        metrics.keys.filter { it.isBefore(cutoff) }.forEach(metrics::remove)
    }

    private fun loadAll(): Map<LocalDate, DailyMetric> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("days") ?: JSONArray()
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val metric = item.toDailyMetric()
                    put(metric.date, metric)
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun saveAll(metrics: Map<LocalDate, DailyMetric>) {
        val json = JSONObject()
        val array = JSONArray()
        metrics.values.sortedBy { it.date }.forEach { array.put(it.toJson()) }
        json.put("days", array)
        file.writeText(json.toString(2))
    }

    private fun DailyMetric.toJson(): JSONObject = JSONObject().apply {
        put("date", date.toString())
        stepsTotal?.let { put("stepsTotal", it) }
        stepsOHealth?.let { put("stepsOHealth", it) }
        caloriesOHealthKcal?.let { put("caloriesOHealthKcal", it) }
        caloriesCoveredMinutes?.let { put("caloriesCoveredMinutes", it) }
        put("workoutCalories", JSONObject(workoutCalories.mapValues { it.value ?: JSONObject.NULL }))
        put("timeInBedMinutes", JSONObject(timeInBedMinutes))
        put("sleepAwakenings", JSONObject(sleepAwakenings))
        put("updatedAt", updatedAt.toString())
    }

    private fun JSONObject.toDailyMetric(): DailyMetric = DailyMetric(
        date = LocalDate.parse(getString("date")),
        stepsTotal = optLongOrNull("stepsTotal"),
        stepsOHealth = optLongOrNull("stepsOHealth"),
        caloriesOHealthKcal = optDoubleOrNull("caloriesOHealthKcal")
            ?: optDoubleOrNull("activeCaloriesOHealthKcal"),
        caloriesCoveredMinutes = optLongOrNull("caloriesCoveredMinutes"),
        // Files written before sessions were keyed hold only totals, which cannot be attributed to
        // sessions after the fact. Those days read back empty and refill on the next sync.
        workoutCalories = optJSONObject("workoutCalories").toDoubleMap(),
        timeInBedMinutes = optJSONObject("timeInBedMinutes").toLongMap(),
        sleepAwakenings = optJSONObject("sleepAwakenings").toIntMap(),
        updatedAt = runCatching { Instant.parse(getString("updatedAt")) }.getOrElse { Instant.now() },
    )

    private fun JSONObject?.toDoubleMap(): Map<String, Double?> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key ->
            if (isNull(key)) null else optDouble(key)
        }
    }

    private fun JSONObject?.toLongMap(): Map<String, Long> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optLong(key) }
    }

    private fun JSONObject?.toIntMap(): Map<String, Int> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optInt(key) }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    companion object {
        const val RETENTION_DAYS = 90
    }
}

class ReportCollector(
    private val metricsStore: MetricsStore,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    private val sessionWorkouts = mutableListOf<WorkoutMetric>()

    fun onDailyActivity(
        date: LocalDate,
        stepsTotal: Long?,
        stepsOHealth: Long?,
        caloriesOHealthKcal: Double?,
        caloriesCoveredMinutes: Long?,
    ) {
        metricsStore.upsertDaily(
            DailyMetric(
                date = date,
                stepsTotal = stepsTotal,
                stepsOHealth = stepsOHealth,
                caloriesOHealthKcal = caloriesOHealthKcal,
                caloriesCoveredMinutes = caloriesCoveredMinutes,
            ),
        )
    }

    fun onWorkoutEnergy(
        sessionId: String,
        title: String?,
        startTime: Instant,
        endTime: Instant,
        caloriesKcal: Double?,
    ) {
        val workout = WorkoutMetric(
            sessionId = sessionId,
            title = title,
            startTime = startTime,
            endTime = endTime,
            caloriesKcal = caloriesKcal,
        )
        sessionWorkouts += workout
        val date = startTime.atZone(zone).toLocalDate()
        metricsStore.addWorkout(date, workout)
    }

    fun onSleepSession(
        sessionId: String,
        startTime: Instant,
        endTime: Instant,
        awakenings: Int?,
    ) {
        // OHealth counts the last minute of a session as its end rather than the minute after it,
        // so a session it displays as 23:11-01:12 arrives here ending at 01:13. Matching that
        // convention keeps every interval identical to the one shown on the watch.
        val timeInBed = (ChronoUnit.MINUTES.between(startTime, endTime) - 1).coerceAtLeast(0)
        val date = endTime.atZone(zone).toLocalDate()
        val key = sessionId.ifEmpty { "$startTime|$endTime" }
        metricsStore.addSleepSession(date, key, timeInBed, awakenings)
    }

    fun sessionWorkouts(): List<WorkoutMetric> = sessionWorkouts.toList()
}
