package dev.gr0mi4.ohealthinsights

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal data class TimeWindow(
    val start: Instant,
    val end: Instant,
)

internal data class SessionWindow(
    val id: String,
    val start: Instant,
    val end: Instant,
    val title: String? = null,
) {
    fun covers(time: Instant): Boolean = !time.isBefore(start) && !time.isAfter(end)
}

internal data class TimedValue(
    val time: Instant,
    val value: Double,
)

internal data class HeartRateSample(
    val time: Instant,
    val beatsPerMinute: Long,
)

internal data class CalorieSample(
    val start: Instant,
    val end: Instant,
    val localDate: LocalDate,
    val kilocalories: Double,
) {
    val minutes: Long
        get() = Duration.between(start, end).toMinutes().coerceAtLeast(1L)
}

internal data class CalorieSamples(
    val samples: List<CalorieSample>,
    val recordType: String?,
)

internal data class SleepSummary(
    val timeInBedMinutes: Long,
    val heartRateSamples: Int,
    val heartRateMinBpm: Long?,
    val heartRateAvgBpm: Double?,
    val minutesToLowestHeartRate: Long?,
    val oxygenSamples: Int,
    val oxygenMinPercent: Double?,
    val oxygenAvgPercent: Double?,
    val oxygenSamplesBelow92: Int,
    val oxygenSamplesBelow90: Int,
    val respiratorySamples: Int,
    val respiratoryAvgRate: Double?,
)

internal data class WorkoutHeartRate(
    val samples: Int,
    val minBpm: Long,
    val avgBpm: Double?,
    val maxBpm: Long,
)

/**
 * The derived-metric rules, kept apart from Health Connect so they can be exercised directly.
 *
 * Every rule here was established by comparing an export against the figures the OHealth app shows,
 * and each one is load-bearing in a way that is not obvious from reading it. They are covered by
 * unit tests for that reason: a plausible-looking simplification here silently changes the numbers.
 */
internal object HealthMetrics {

    // Awakening estimation. The threshold is deliberately low: measured nights sit within a few
    // beats of their own baseline, so a conventional spike rule would report nothing at all.
    const val SLEEP_EDGE_MINUTES = 10L
    const val MINIMUM_SLEEP_SAMPLES = 20
    const val AWAKENING_BEATS_ABOVE_BASELINE = 6
    const val AWAKENING_GAP_MINUTES = 6L

    /**
     * Drops any record whose span strictly contains another record's span.
     *
     * OHealth emits one summary record per workout alongside the per-minute records covering the
     * same window; keeping both double-counts the session.
     */
    fun dropSummaryDuplicates(samples: List<CalorieSample>): List<CalorieSample> {
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

    /** Buckets by the day the record itself was recorded in, not by UTC and not by the phone's zone. */
    fun localDateOf(instant: Instant, offset: ZoneOffset?): LocalDate =
        if (offset != null) {
            instant.atOffset(offset).toLocalDate()
        } else {
            instant.atZone(ZoneId.systemDefault()).toLocalDate()
        }

    fun localDayBounds(range: TimeWindow, zone: ZoneId): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDateTime.ofInstant(range.start, zone).toLocalDate().atStartOfDay()
        val end = LocalDateTime.ofInstant(range.end.minusNanos(1), zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay()
        return start to end
    }

    /**
     * OHealth counts the last minute of a session as its end rather than the minute after it, so a
     * session it displays as 23:11-01:12 arrives ending at 01:13.
     */
    fun timeInBedMinutes(start: Instant, end: Instant): Long =
        (ChronoUnit.MINUTES.between(start, end) - 1).coerceAtLeast(0)

    /**
     * Estimated count of night-time awakenings, from heart rate alone.
     *
     * OHealth marks awake segments in its own app and subtracts them from the night, but Health
     * Connect receives `stages=[]` for every session, so the segmentation is simply not exported.
     * Heart rate is the only remaining signal, and at roughly one sample every two minutes a short
     * awakening shows up as one to three slightly raised readings - a few beats above the night's
     * own baseline, not a spike. This therefore returns an estimate that tracks whether a night was
     * settled or broken; it cannot reproduce the app's figure and must not be presented as sleep
     * staging.
     *
     * The opening and closing minutes are ignored because falling asleep and waking always raise
     * heart rate and would otherwise be counted on every night.
     */
    fun estimateAwakenings(session: SessionWindow, samples: List<HeartRateSample>): Int? {
        val core = samples.filter { sample ->
            !sample.time.isBefore(session.start.plus(SLEEP_EDGE_MINUTES, ChronoUnit.MINUTES)) &&
                sample.time.isBefore(session.end.minus(SLEEP_EDGE_MINUTES, ChronoUnit.MINUTES))
        }
        if (core.size < MINIMUM_SLEEP_SAMPLES) return null
        val baseline = core.map { it.beatsPerMinute }.sorted()[core.size / 2]
        val threshold = baseline + AWAKENING_BEATS_ABOVE_BASELINE
        var awakenings = 0
        var previousElevated: Instant? = null
        core.sortedBy { it.time }.forEach { sample ->
            if (sample.beatsPerMinute < threshold) return@forEach
            val previous = previousElevated
            if (previous == null ||
                ChronoUnit.MINUTES.between(previous, sample.time) > AWAKENING_GAP_MINUTES
            ) {
                awakenings += 1
            }
            previousElevated = sample.time
        }
        return awakenings
    }

    /**
     * The numbers one night reduces to.
     *
     * There are no sleep stages and no HRV in the export, so the shape of the nocturnal heart rate
     * curve - how low it went and how quickly - plus oxygen dips and breathing rate are the whole of
     * the available evidence.
     */
    fun sleepSummary(
        session: SessionWindow,
        heartRate: List<HeartRateSample>,
        oxygen: List<TimedValue>,
        respiratory: List<TimedValue>,
    ): SleepSummary {
        val hr = heartRate.filter { session.covers(it.time) }.sortedBy { it.time }
        val spo2 = oxygen.filter { session.covers(it.time) }
        val breathing = respiratory.filter { session.covers(it.time) }
        val lowest = hr.minByOrNull { it.beatsPerMinute }
        return SleepSummary(
            timeInBedMinutes = timeInBedMinutes(session.start, session.end),
            heartRateSamples = hr.size,
            heartRateMinBpm = lowest?.beatsPerMinute,
            heartRateAvgBpm = hr.map { it.beatsPerMinute }.averageOrNull()?.rounded(),
            minutesToLowestHeartRate = lowest?.let { ChronoUnit.MINUTES.between(session.start, it.time) },
            oxygenSamples = spo2.size,
            oxygenMinPercent = spo2.minOfOrNull { it.value }?.rounded(),
            oxygenAvgPercent = spo2.map { it.value }.averageOrNull()?.rounded(),
            oxygenSamplesBelow92 = spo2.count { it.value < 92.0 },
            oxygenSamplesBelow90 = spo2.count { it.value < 90.0 },
            respiratorySamples = breathing.size,
            respiratoryAvgRate = breathing.map { it.value }.averageOrNull()?.rounded(),
        )
    }

    fun workoutHeartRate(workout: SessionWindow, heartRate: List<HeartRateSample>): WorkoutHeartRate? {
        val hr = heartRate.filter { workout.covers(it.time) }
        if (hr.isEmpty()) return null
        return WorkoutHeartRate(
            samples = hr.size,
            minBpm = hr.minOf { it.beatsPerMinute },
            avgBpm = hr.map { it.beatsPerMinute }.averageOrNull()?.rounded(),
            maxBpm = hr.maxOf { it.beatsPerMinute },
        )
    }

    /** Calories a workout is credited with: the shared samples sliced by explicit containment. */
    fun caloriesWithin(window: SessionWindow, samples: List<CalorieSample>): List<CalorieSample> =
        samples.filter { !it.start.isBefore(window.start) && !it.end.isAfter(window.end) }

    fun Collection<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else sumOf { it.toDouble() } / size

    fun Double.rounded(): Double = Math.round(this * 10.0) / 10.0
}
