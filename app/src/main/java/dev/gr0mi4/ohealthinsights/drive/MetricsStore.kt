package dev.gr0mi4.ohealthinsights.drive

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

data class DailyMetric(
    val date: LocalDate,
    val stepsTotal: Long? = null,
    val stepsOHealth: Long? = null,
    val totalCaloriesKcal: Double? = null,
    val workoutCount: Int = 0,
    val workoutCaloriesKcal: Double? = null,
    val sleepMinutes: Long? = null,
    val updatedAt: Instant = Instant.now(),
)

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
                workoutCount = maxOf(existing?.workoutCount ?: 0, metric.workoutCount),
                workoutCaloriesKcal = metric.workoutCaloriesKcal ?: existing?.workoutCaloriesKcal,
                sleepMinutes = metric.sleepMinutes ?: existing?.sleepMinutes,
                stepsTotal = metric.stepsTotal ?: existing?.stepsTotal,
                stepsOHealth = metric.stepsOHealth ?: existing?.stepsOHealth,
                totalCaloriesKcal = metric.totalCaloriesKcal ?: existing?.totalCaloriesKcal,
                updatedAt = metric.updatedAt,
            )
            prune(metrics)
            saveAll(metrics)
        }
    }

    fun addWorkout(date: LocalDate, workout: WorkoutMetric) {
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            val existing = metrics[date] ?: DailyMetric(date = date)
            val addedCalories = (existing.workoutCaloriesKcal ?: 0.0) + (workout.caloriesKcal ?: 0.0)
            metrics[date] = existing.copy(
                workoutCount = existing.workoutCount + 1,
                workoutCaloriesKcal = if (workout.caloriesKcal != null) addedCalories else existing.workoutCaloriesKcal,
                updatedAt = Instant.now(),
            )
            prune(metrics)
            saveAll(metrics)
        }
    }

    fun addSleepMinutes(date: LocalDate, minutes: Long) {
        synchronized(lock) {
            val metrics = loadAll().toMutableMap()
            val existing = metrics[date] ?: DailyMetric(date = date)
            metrics[date] = existing.copy(
                sleepMinutes = (existing.sleepMinutes ?: 0L) + minutes,
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
        totalCaloriesKcal?.let { put("totalCaloriesKcal", it) }
        put("workoutCount", workoutCount)
        workoutCaloriesKcal?.let { put("workoutCaloriesKcal", it) }
        sleepMinutes?.let { put("sleepMinutes", it) }
        put("updatedAt", updatedAt.toString())
    }

    private fun JSONObject.toDailyMetric(): DailyMetric = DailyMetric(
        date = LocalDate.parse(getString("date")),
        stepsTotal = optLongOrNull("stepsTotal"),
        stepsOHealth = optLongOrNull("stepsOHealth"),
        totalCaloriesKcal = optDoubleOrNull("totalCaloriesKcal"),
        workoutCount = optInt("workoutCount", 0),
        workoutCaloriesKcal = optDoubleOrNull("workoutCaloriesKcal"),
        sleepMinutes = optLongOrNull("sleepMinutes"),
        updatedAt = runCatching { Instant.parse(getString("updatedAt")) }.getOrElse { Instant.now() },
    )

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
        totalCaloriesKcal: Double?,
    ) {
        metricsStore.upsertDaily(
            DailyMetric(
                date = date,
                stepsTotal = stepsTotal,
                stepsOHealth = stepsOHealth,
                totalCaloriesKcal = totalCaloriesKcal,
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

    fun onSleepSession(startTime: Instant, endTime: Instant) {
        val minutes = ChronoUnit.MINUTES.between(startTime, endTime).coerceAtLeast(0)
        val date = endTime.atZone(zone).toLocalDate()
        metricsStore.addSleepMinutes(date, minutes)
    }

    fun sessionWorkouts(): List<WorkoutMetric> = sessionWorkouts.toList()
}
