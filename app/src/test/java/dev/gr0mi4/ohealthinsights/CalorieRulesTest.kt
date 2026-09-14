package dev.gr0mi4.ohealthinsights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calorie rules, pinned.
 *
 * Every case here comes from a discrepancy that was actually observed against the OHealth app, not
 * from imagining what could go wrong. A change that breaks one of these changes the reported
 * numbers, however reasonable it looks in isolation.
 */
class CalorieRulesTest {

    private fun sample(from: String, to: String, kcal: Double, offset: String = "+02:00") =
        CalorieSample(
            start = Instant.parse(from),
            end = Instant.parse(to),
            localDate = HealthMetrics.localDateOf(Instant.parse(from), ZoneOffset.of(offset)),
            kilocalories = kcal,
        )

    @Test
    fun `a workout summary record spanning per-minute records is dropped`() {
        // Exactly the shape OHealth wrote on 2026-09-13 at 20:31 local: one 21-minute summary of
        // 165 kcal laid over the per-minute records covering the same window.
        val perMinute = (0 until 21).map { minute ->
            sample(
                from = "2026-09-13T18:${(31 + minute).toString().padStart(2, '0')}:00Z",
                to = "2026-09-13T18:${(32 + minute).toString().padStart(2, '0')}:00Z",
                kcal = 8.0,
            )
        }
        val summary = sample("2026-09-13T18:31:00Z", "2026-09-13T18:52:00Z", 165.0)

        val kept = HealthMetrics.dropSummaryDuplicates(perMinute + summary)

        assertEquals(21, kept.size)
        assertEquals(168.0, kept.sumOf { it.kilocalories }, 0.001)
    }

    @Test
    fun `records that merely overlap are both kept`() {
        val first = sample("2026-09-13T10:00:00Z", "2026-09-13T10:10:00Z", 10.0)
        val second = sample("2026-09-13T10:05:00Z", "2026-09-13T10:15:00Z", 10.0)

        assertEquals(2, HealthMetrics.dropSummaryDuplicates(listOf(first, second)).size)
    }

    @Test
    fun `identical spans are both kept because neither contains the other`() {
        val first = sample("2026-09-13T10:00:00Z", "2026-09-13T10:01:00Z", 3.0)
        val second = sample("2026-09-13T10:00:00Z", "2026-09-13T10:01:00Z", 4.0)

        assertEquals(2, HealthMetrics.dropSummaryDuplicates(listOf(first, second)).size)
    }

    @Test
    fun `a container is dropped regardless of the order it arrives in`() {
        val inner = sample("2026-09-13T10:02:00Z", "2026-09-13T10:03:00Z", 5.0)
        val outer = sample("2026-09-13T10:00:00Z", "2026-09-13T10:10:00Z", 50.0)

        listOf(listOf(inner, outer), listOf(outer, inner)).forEach { input ->
            val kept = HealthMetrics.dropSummaryDuplicates(input)
            assertEquals(1, kept.size)
            assertEquals(5.0, kept.single().kilocalories, 0.001)
        }
    }

    @Test
    fun `a single record survives`() {
        val only = sample("2026-09-13T10:00:00Z", "2026-09-13T10:01:00Z", 1.0)
        assertEquals(listOf(only), HealthMetrics.dropSummaryDuplicates(listOf(only)))
    }

    @Test
    fun `records are bucketed by their own zone offset, not by UTC`() {
        // 22:30 UTC on the 10th is 00:30 on the 11th in Warsaw. Bucketing by UTC moved a whole
        // evening into the previous day and was why the first analysis reported 12 hours of sleep.
        val late = Instant.parse("2026-09-10T22:30:00Z")

        assertEquals(LocalDate.parse("2026-09-11"), HealthMetrics.localDateOf(late, ZoneOffset.of("+02:00")))
        assertEquals(LocalDate.parse("2026-09-10"), HealthMetrics.localDateOf(late, ZoneOffset.UTC))
    }

    @Test
    fun `a minute-long record counts as one covered minute`() {
        assertEquals(1L, sample("2026-09-13T10:00:00Z", "2026-09-13T10:01:00Z", 1.0).minutes)
    }

    @Test
    fun `a sub-minute record still counts as one covered minute`() {
        assertEquals(1L, sample("2026-09-13T10:00:00Z", "2026-09-13T10:00:30Z", 1.0).minutes)
    }

    @Test
    fun `workout calories take only records fully inside the session`() {
        // 500 records straddle a workout boundary across the history; counting them in full was
        // worth up to 2% of every workout figure before containment was made explicit.
        val workout = SessionWindow("w", Instant.parse("2026-09-13T18:31:00Z"), Instant.parse("2026-09-13T18:52:00Z"))
        val inside = sample("2026-09-13T18:40:00Z", "2026-09-13T18:41:00Z", 9.0)
        val straddlingStart = sample("2026-09-13T18:30:00Z", "2026-09-13T18:32:00Z", 9.0)
        val straddlingEnd = sample("2026-09-13T18:51:00Z", "2026-09-13T18:53:00Z", 9.0)

        val within = HealthMetrics.caloriesWithin(workout, listOf(straddlingStart, inside, straddlingEnd))

        assertEquals(listOf(inside), within)
    }

    @Test
    fun `an empty set of samples yields no workout heart rate line`() {
        val workout = SessionWindow("w", Instant.parse("2026-09-13T18:31:00Z"), Instant.parse("2026-09-13T18:52:00Z"))
        assertNull(HealthMetrics.workoutHeartRate(workout, emptyList()))
    }
}
