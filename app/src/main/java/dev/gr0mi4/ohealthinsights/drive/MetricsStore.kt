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
    val updatedAt: Instant = Instant.now(),
) {
    val workoutCount: Int get() = workouts.size

    val workoutCaloriesKcal: Double?
        get() = workouts.values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    val sleepMinutes: Long?
        get() = sleepSessions.values.takeIf { it.isNotEmpty() }?.sum()
}

data class WorkoutMetric(
    val sessionId: String,
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val caloriesKcal: Double?,
)

/** One export's worth of changes for a single day, applied in a single pass. */
data class DailyUpdate(
    val stepsTotal: Long? = null,
    val stepsOHealth: Long? = null,
    val totalCaloriesKcal: Double? = null,
    val workouts: Map<String, Double?> = emptyMap(),
    val sleepSessions: Map<String, Long> = emptyMap(),
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
            val array = JSONObject(file.readText()).optJSONArray("days") ?: JSONArray()
            buildMap {
                for (index in 0 until array.length()) {
                    val metric = array.getJSONObject(index).toDailyMetric()
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
        val payload = JSONObject().put("days", array).toString()

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
        put("updatedAt", updatedAt.toString())
    }

    /**
     * Reads both the current and the pre-0.6 layout. The old one stored running totals that double
     * counted every replay, and its session ids are gone, so those two fields start over; steps and
     * calories were always overwritten rather than accumulated and carry across intact.
     */
    private fun JSONObject.toDailyMetric(): DailyMetric = DailyMetric(
        date = LocalDate.parse(getString("date")),
        stepsTotal = optLongOrNull("stepsTotal"),
        stepsOHealth = optLongOrNull("stepsOHealth"),
        totalCaloriesKcal = optDoubleOrNull("totalCaloriesKcal"),
        workouts = optJSONObject("workouts").toNullableDoubleMap(),
        sleepSessions = optJSONObject("sleepSessions").toLongMap(),
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
        val date = startTime.atZone(zone).toLocalDate()
        drafts.getOrPut(date) { DayDraft() }.workouts[sessionId] = caloriesKcal
    }

    fun onSleepSession(sessionId: String, startTime: Instant, endTime: Instant) {
        val minutes = ChronoUnit.MINUTES.between(startTime, endTime).coerceAtLeast(0)
        val date = endTime.atZone(zone).toLocalDate()
        drafts.getOrPut(date) { DayDraft() }.sleepSessions[sessionId] = minutes
    }

    /** Persists everything buffered so far. Safe to call twice; the second call merges the same ids. */
    fun flush() {
        metricsStore.merge(
            drafts.mapValues { (_, draft) ->
                DailyUpdate(
                    stepsTotal = draft.stepsTotal,
                    stepsOHealth = draft.stepsOHealth,
                    totalCaloriesKcal = draft.totalCaloriesKcal,
                    workouts = draft.workouts.toMap(),
                    sleepSessions = draft.sleepSessions.toMap(),
                )
            },
        )
    }

    fun sessionWorkouts(): List<WorkoutMetric> = sessionWorkouts.values.toList()
}
