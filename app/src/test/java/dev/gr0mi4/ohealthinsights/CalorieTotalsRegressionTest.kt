package dev.gr0mi4.ohealthinsights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole calorie pipeline against real records, pinned to the figures the OHealth app shows.
 *
 * The fixture is every OHealth calorie record the watch wrote around 2026-09-11 to 13, taken from a
 * full diagnostic export. The expected totals were read off the watch. Establishing them took a day
 * of comparing an export against the app, and nothing else in the codebase records what the right
 * answer is.
 */
class CalorieTotalsRegressionTest {

    private val samples: List<CalorieSample> by lazy {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
            "missing fixture $FIXTURE"
        }
        stream.bufferedReader().useLines { lines ->
            lines.drop(1)
                .filter { it.isNotBlank() }
                .map { line ->
                    val (startUtc, endUtc, zoneOffset, kilocalories) = line.split(",")
                    val start = Instant.parse(startUtc + "Z")
                    CalorieSample(
                        start = start,
                        end = Instant.parse(endUtc + "Z"),
                        localDate = HealthMetrics.localDateOf(start, ZoneOffset.of(zoneOffset)),
                        kilocalories = kilocalories.toDouble(),
                    )
                }
                .toList()
        }
    }

    private fun totalFor(date: String): Double = HealthMetrics.dropSummaryDuplicates(samples)
        .filter { it.localDate == LocalDate.parse(date) }
        .sumOf { it.kilocalories }

    @Test
    fun `the fixture holds the records it is supposed to`() {
        assertTrue("fixture looks truncated: ${samples.size}", samples.size > 1_300)
    }

    @Test
    fun `2026-09-11 matches the watch`() = assertEquals(1270.0, totalFor("2026-09-11"), 0.5)

    @Test
    fun `2026-09-12 matches the watch`() = assertEquals(1047.0, totalFor("2026-09-12"), 0.5)

    @Test
    fun `2026-09-13 matches the watch, the day with a workout`() =
        assertEquals(910.0, totalFor("2026-09-13"), 0.5)

    @Test
    fun `without deduplication the workout day is overcounted`() {
        // Guards the guard: if dropSummaryDuplicates ever became a no-op the day would read 1075,
        // and every assertion above would need to be wrong for this one to pass.
        val undeduplicated = samples
            .filter { it.localDate == LocalDate.parse("2026-09-13") }
            .sumOf { it.kilocalories }

        assertEquals(1075.0, undeduplicated, 0.5)
    }

    private companion object {
        const val FIXTURE = "ohealth_calories_2026-09-11_13.csv"
    }
}

