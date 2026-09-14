package dev.gr0mi4.ohealthinsights.drive

import java.time.Instant
import java.time.LocalDate
import dev.gr0mi4.ohealthinsights.HealthMetrics
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
    val weightKilograms: Double? = null,
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

/** A day whose stored value was replaced by a different one, and when. */
data class MetricChange(
    val changedAt: Instant,
    val date: LocalDate,
    val field: String,
    val before: String,
    val after: String,
)

data class WorkoutMetric(
    val sessionId: String,
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val caloriesKcal: Double?,
)

/**
 * Takes the file rather than a Context so the storage behaviour can be exercised directly. Two
 * defects have lived in here - counters that grew on every replay, and a write that truncated the
 * file in place - and neither was reachable by a test while this class needed an Android Context.
 */
class MetricsStore(private val file: java.io.File) {

    constructor(context: android.content.Context) :
        this(java.io.File(context.filesDir, FILE_NAME))

    private val changeLogFile = java.io.File(file.parentFile, CHANGE_LOG_FILE_NAME)
    private val lock = Any()

    /**
     * Set for the duration of an export, so the file is written once at the end.
     *
     * Every update used to read, parse, serialise and write the whole file: an initial sync covering
     * three years did that about 2400 times to change one field at a time.
     */
    private var batch: MutableMap<LocalDate, DailyMetric>? = null
    private val pendingChanges = mutableListOf<MetricChange>()

    fun beginBatch() {
        synchronized(lock) {
            if (batch == null) batch = loadAll().toMutableMap()
        }
    }

    fun commitBatch() {
        synchronized(lock) {
            batch?.let { metrics ->
                prune(metrics)
                saveAll(metrics)
            }
            batch = null
            flushChanges()
        }
    }

    private fun mutate(date: LocalDate, transform: (DailyMetric?) -> DailyMetric) {
        synchronized(lock) {
            val open = batch
            if (open != null) {
                open[date] = transform(open[date])
                return
            }
            val metrics = loadAll().toMutableMap()
            metrics[date] = transform(metrics[date])
            prune(metrics)
            saveAll(metrics)
            flushChanges()
        }
    }

    private fun read(): Map<LocalDate, DailyMetric> = synchronized(lock) { batch ?: loadAll() }

    /**
     * Notes a day whose value moved, so an unexpected change can be traced afterwards.
     *
     * A sync that wrote 37 kcal over a correct 820 was invisible until the number was noticed by
     * eye; the old value existed nowhere. Only a value replacing a different one is recorded -
     * filling in a blank is not a change.
     */
    private fun recordChanges(existing: DailyMetric?, incoming: DailyMetric) {
        if (existing == null) return
        // A day still in progress moves every sync - steps went 4824, 4835, 4837, 4844 in one
        // afternoon - and none of that is a correction. Only a finished day changing is worth a
        // line, or the log fills with the present.
        if (!incoming.date.isBefore(LocalDate.now())) return
        val at = Instant.now()
        fun note(field: String, before: Any?, after: Any?) {
            if (before == null || after == null || before == after) return
            pendingChanges += MetricChange(at, incoming.date, field, before.toString(), after.toString())
        }
        note("stepsTotal", existing.stepsTotal, incoming.stepsTotal)
        note("stepsOHealth", existing.stepsOHealth, incoming.stepsOHealth)
        note("caloriesOHealthKcal", existing.caloriesOHealthKcal, incoming.caloriesOHealthKcal)
        note("caloriesCoveredMinutes", existing.caloriesCoveredMinutes, incoming.caloriesCoveredMinutes)
        note("weightKilograms", existing.weightKilograms, incoming.weightKilograms)
    }

    fun changeLog(): List<MetricChange> = synchronized(lock) {
        if (!changeLogFile.exists()) return emptyList()
        changeLogFile.readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size < 5) return@mapNotNull null
                runCatching {
                    MetricChange(
                        changedAt = Instant.parse(parts[0]),
                        date = LocalDate.parse(parts[1]),
                        field = parts[2],
                        before = parts[3],
                        after = parts[4],
                    )
                }.getOrNull()
            }
    }

    private fun flushChanges() {
        if (pendingChanges.isEmpty()) return
        val existing = changeLog()
        val combined = (existing + pendingChanges).takeLast(MAX_CHANGE_ENTRIES)
        pendingChanges.clear()
        val text = buildString {
            appendLine("changedAt,date,field,before,after")
            combined.forEach { appendLine("${it.changedAt},${it.date},${it.field},${it.before},${it.after}") }
        }
        writeAtomically(changeLogFile, text)
    }

    fun upsertDaily(metric: DailyMetric) = mutate(metric.date) { existing ->
        recordChanges(existing, metric)
        metric.copy(
            workoutCalories = existing?.workoutCalories ?: emptyMap(),
            timeInBedMinutes = existing?.timeInBedMinutes ?: emptyMap(),
            sleepAwakenings = existing?.sleepAwakenings ?: emptyMap(),
            stepsTotal = metric.stepsTotal ?: existing?.stepsTotal,
            stepsOHealth = metric.stepsOHealth ?: existing?.stepsOHealth,
            caloriesOHealthKcal = metric.caloriesOHealthKcal ?: existing?.caloriesOHealthKcal,
            caloriesCoveredMinutes = metric.caloriesCoveredMinutes ?: existing?.caloriesCoveredMinutes,
            weightKilograms = metric.weightKilograms ?: existing?.weightKilograms,
            updatedAt = metric.updatedAt,
        )
    }

    fun addWorkout(date: LocalDate, workout: WorkoutMetric) {
        val key = workout.sessionId.ifEmpty { "${workout.startTime}|${workout.endTime}" }
        mutate(date) { existing ->
            val day = existing ?: DailyMetric(date = date)
            day.copy(
                workoutCalories = day.workoutCalories + (key to workout.caloriesKcal),
                updatedAt = Instant.now(),
            )
        }
    }

    fun addSleepSession(date: LocalDate, key: String, minutes: Long, awakenings: Int?) {
        mutate(date) { existing ->
            val day = existing ?: DailyMetric(date = date)
            day.copy(
                timeInBedMinutes = day.timeInBedMinutes + (key to minutes),
                sleepAwakenings = if (awakenings == null) {
                    day.sleepAwakenings
                } else {
                    day.sleepAwakenings + (key to awakenings)
                },
                updatedAt = Instant.now(),
            )
        }
    }

    fun recentDays(days: Int = 14): List<DailyMetric> {
        val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
        return read().values
            .filter { !it.date.isBefore(cutoff) }
            .sortedBy { it.date }
    }

    fun allMetrics(): List<DailyMetric> = read().values.sortedBy { it.date }

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

    /**
     * Writes through a temporary file and renames it over the original.
     *
     * A background sync can be killed at any point, and writing in place left a truncated file that
     * [loadAll] then read as empty - silently discarding the retained history.
     */
    private fun saveAll(metrics: Map<LocalDate, DailyMetric>) {
        val json = JSONObject()
        val array = JSONArray()
        metrics.values.sortedBy { it.date }.forEach { array.put(it.toJson()) }
        json.put("days", array)
        writeAtomically(file, json.toString(2))
    }

    private fun writeAtomically(target: java.io.File, text: String) {
        val temp = java.io.File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun DailyMetric.toJson(): JSONObject = JSONObject().apply {
        put("date", date.toString())
        stepsTotal?.let { put("stepsTotal", it) }
        stepsOHealth?.let { put("stepsOHealth", it) }
        caloriesOHealthKcal?.let { put("caloriesOHealthKcal", it) }
        caloriesCoveredMinutes?.let { put("caloriesCoveredMinutes", it) }
        weightKilograms?.let { put("weightKilograms", it) }
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
        weightKilograms = optDoubleOrNull("weightKilograms"),
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
        /**
         * Ten years rather than a quarter.
         *
         * This was 90 days, which capped the whole point of the project: a full sync computed a row
         * for every one of 1413 days and then discarded 94% of them on the way to disk, so no
         * report could compare this September with last. A day costs about 400 bytes, so the entire
         * history is around half a megabyte - less than the app spends on a single upload.
         */
        const val RETENTION_DAYS = 3_650

        /** Enough to cover several years of corrections without the log becoming its own problem. */
        const val MAX_CHANGE_ENTRIES = 5_000
        const val FILE_NAME = "ohealth_daily_metrics.json"
        const val CHANGE_LOG_FILE_NAME = "ohealth_metric_changes.csv"
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
        weightKilograms: Double?,
    ) {
        metricsStore.upsertDaily(
            DailyMetric(
                date = date,
                stepsTotal = stepsTotal,
                stepsOHealth = stepsOHealth,
                caloriesOHealthKcal = caloriesOHealthKcal,
                caloriesCoveredMinutes = caloriesCoveredMinutes,
                weightKilograms = weightKilograms,
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
        val timeInBed = HealthMetrics.timeInBedMinutes(startTime, endTime)
        val date = endTime.atZone(zone).toLocalDate()
        val key = sessionId.ifEmpty { "$startTime|$endTime" }
        metricsStore.addSleepSession(date, key, timeInBed, awakenings)
    }

    fun sessionWorkouts(): List<WorkoutMetric> = sessionWorkouts.toList()
}
