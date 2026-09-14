package dev.gr0mi4.ohealthinsights

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSummaryTest {

    private val night = SessionWindow(
        id = "n",
        start = Instant.parse("2026-09-11T00:11:00Z"),
        end = Instant.parse("2026-09-11T04:12:00Z"),
    )

    private fun hr(offsetMinutes: Long, bpm: Long) =
        HeartRateSample(night.start.plus(offsetMinutes, ChronoUnit.MINUTES), bpm)

    private fun spo2(offsetMinutes: Long, percent: Double) =
        TimedValue(night.start.plus(offsetMinutes, ChronoUnit.MINUTES), percent)

    @Test
    fun `summary reports the lowest reading and how long it took to reach it`() {
        val samples = listOf(hr(0, 60), hr(30, 52), hr(90, 43), hr(150, 48))

        val summary = HealthMetrics.sleepSummary(night, samples, emptyList(), emptyList())

        assertEquals(4, summary.heartRateSamples)
        assertEquals(43L, summary.heartRateMinBpm)
        assertEquals(90L, summary.minutesToLowestHeartRate)
        assertEquals(50.8, summary.heartRateAvgBpm!!, 0.05)
    }

    @Test
    fun `samples outside the session are excluded`() {
        val before = HeartRateSample(night.start.minus(5, ChronoUnit.MINUTES), 90)
        val after = HeartRateSample(night.end.plus(5, ChronoUnit.MINUTES), 95)

        val summary = HealthMetrics.sleepSummary(night, listOf(before, hr(60, 45), after), emptyList(), emptyList())

        assertEquals(1, summary.heartRateSamples)
        assertEquals(45L, summary.heartRateMinBpm)
    }

    @Test
    fun `oxygen thresholds are counted strictly below the boundary`() {
        val readings = listOf(spo2(10, 92.0), spo2(20, 91.9), spo2(30, 90.0), spo2(40, 89.5))

        val summary = HealthMetrics.sleepSummary(night, emptyList(), readings, emptyList())

        assertEquals(4, summary.oxygenSamples)
        assertEquals(3, summary.oxygenSamplesBelow92)
        assertEquals(1, summary.oxygenSamplesBelow90)
        assertEquals(89.5, summary.oxygenMinPercent!!, 0.001)
    }

    @Test
    fun `a night with no readings reports nulls rather than zeroes`() {
        val summary = HealthMetrics.sleepSummary(night, emptyList(), emptyList(), emptyList())

        assertEquals(0, summary.heartRateSamples)
        assertNull(summary.heartRateMinBpm)
        assertNull(summary.heartRateAvgBpm)
        assertNull(summary.minutesToLowestHeartRate)
        assertNull(summary.oxygenMinPercent)
        assertNull(summary.respiratoryAvgRate)
        assertEquals(240L, summary.timeInBedMinutes)
    }

    @Test
    fun `workout heart rate spans the session`() {
        val workout = SessionWindow("w", night.start, night.start.plus(30, ChronoUnit.MINUTES))
        val samples = listOf(hr(0, 110), hr(10, 150), hr(20, 130), hr(45, 60))

        val summary = checkNotNull(HealthMetrics.workoutHeartRate(workout, samples))

        assertEquals(3, summary.samples)
        assertEquals(110L, summary.minBpm)
        assertEquals(150L, summary.maxBpm)
        assertEquals(130.0, summary.avgBpm!!, 0.05)
    }

    @Test
    fun `local day bounds cover whole days in the phone's zone`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val range = TimeWindow(
            start = Instant.parse("2026-09-11T09:00:00Z"),
            end = Instant.parse("2026-09-12T09:00:00Z"),
        )

        val (start, end) = HealthMetrics.localDayBounds(range, zone)

        assertEquals("2026-09-11T00:00", start.toString())
        assertEquals("2026-09-13T00:00", end.toString())
    }

    @Test
    fun `a range ending exactly at midnight does not pull in the next day`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val range = TimeWindow(
            start = Instant.parse("2026-09-10T22:00:00Z"),
            end = Instant.parse("2026-09-11T22:00:00Z"),
        )

        val (start, end) = HealthMetrics.localDayBounds(range, zone)

        assertEquals("2026-09-11T00:00", start.toString())
        assertEquals("2026-09-12T00:00", end.toString())
    }
}

/**
 * The regression that produced a 37 kcal row for a day the sync never covered.
 *
 * A workout starting the previous day pulled the calorie read back past the range, those samples
 * carried the previous day's date, and a daily row was written from them alone: a fragment of the
 * day with no steps. The date was then marked written, so the range that actually covered it never
 * produced a row, and the fragment overwrote the correct figure in the report.
 */
class DailyRowRangeTest {

    private val zone = java.time.ZoneId.of("Europe/Warsaw")

    /** A range covering only 2026-09-11 in local time. */
    private val range = TimeWindow(
        start = Instant.parse("2026-09-10T22:00:00Z"),
        end = Instant.parse("2026-09-11T22:00:00Z"),
    )

    private fun datesFor(vararg candidates: String): List<java.time.LocalDate> {
        val (localStart, localEnd) = HealthMetrics.localDayBounds(range, zone)
        return HealthMetrics.dailyDatesWithin(
            localStart = localStart,
            localEnd = localEnd,
            candidates = candidates.map(java.time.LocalDate::parse).toSet(),
        )
    }

    @Test
    fun `a date dragged in from before the range is not written`() {
        assertEquals(listOf(java.time.LocalDate.parse("2026-09-11")), datesFor("2026-09-10", "2026-09-11"))
    }

    @Test
    fun `a date after the range is not written either`() {
        assertEquals(listOf(java.time.LocalDate.parse("2026-09-11")), datesFor("2026-09-11", "2026-09-12"))
    }

    @Test
    fun `the days the range does cover are all written`() {
        val wide = TimeWindow(
            start = Instant.parse("2026-09-10T22:00:00Z"),
            end = Instant.parse("2026-09-13T22:00:00Z"),
        )
        val (localStart, localEnd) = HealthMetrics.localDayBounds(wide, zone)

        val dates = HealthMetrics.dailyDatesWithin(
            localStart = localStart,
            localEnd = localEnd,
            candidates = setOf(
                java.time.LocalDate.parse("2026-09-09"),
                java.time.LocalDate.parse("2026-09-11"),
                java.time.LocalDate.parse("2026-09-12"),
                java.time.LocalDate.parse("2026-09-13"),
                java.time.LocalDate.parse("2026-09-14"),
            ),
        )

        assertEquals(
            listOf("2026-09-11", "2026-09-12", "2026-09-13").map(java.time.LocalDate::parse),
            dates,
        )
    }

    @Test
    fun `dates come back in order`() {
        val wide = TimeWindow(
            start = Instant.parse("2026-09-10T22:00:00Z"),
            end = Instant.parse("2026-09-13T22:00:00Z"),
        )
        val (localStart, localEnd) = HealthMetrics.localDayBounds(wide, zone)

        val dates = HealthMetrics.dailyDatesWithin(
            localStart = localStart,
            localEnd = localEnd,
            candidates = setOf(
                java.time.LocalDate.parse("2026-09-13"),
                java.time.LocalDate.parse("2026-09-11"),
                java.time.LocalDate.parse("2026-09-12"),
            ),
        )

        assertEquals(dates.sorted(), dates)
    }
}
