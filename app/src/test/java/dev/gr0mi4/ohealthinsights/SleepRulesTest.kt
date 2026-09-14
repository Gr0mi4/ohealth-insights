package dev.gr0mi4.ohealthinsights

import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepRulesTest {

    /** The three sessions OHealth shows for 2026-09-11, taken from a real export. */
    private val nightOfTheEleventh = listOf(
        SessionWindow("a", Instant.parse("2026-09-10T21:11:00Z"), Instant.parse("2026-09-10T23:13:00Z")),
        SessionWindow("b", Instant.parse("2026-09-11T00:11:00Z"), Instant.parse("2026-09-11T04:12:00Z")),
        SessionWindow("c", Instant.parse("2026-09-11T13:44:00Z"), Instant.parse("2026-09-11T14:49:00Z")),
    )

    @Test
    fun `a session is counted to its last occupied minute`() {
        // The watch displays 23:11-01:12 for a record that arrives ending at 01:13.
        assertEquals(
            121L,
            HealthMetrics.timeInBedMinutes(
                Instant.parse("2026-09-10T21:11:00Z"),
                Instant.parse("2026-09-10T23:13:00Z"),
            ),
        )
    }

    @Test
    fun `the three sessions of 2026-09-11 total the intervals the watch displays`() {
        val total = nightOfTheEleventh.sumOf { HealthMetrics.timeInBedMinutes(it.start, it.end) }

        // 425, not the 417 the watch reports: the missing 8 minutes are awake segments that Health
        // Connect never receives, and this value is time in bed rather than time asleep.
        assertEquals(425L, total)
    }

    @Test
    fun `a zero-length session cannot report negative minutes`() {
        val moment = Instant.parse("2026-09-11T00:00:00Z")
        assertEquals(0L, HealthMetrics.timeInBedMinutes(moment, moment))
    }

    @Test
    fun `too few samples leaves the awakening estimate unknown rather than zero`() {
        val session = nightOfTheEleventh[1]
        val sparse = (0 until 5).map {
            HeartRateSample(session.start.plus((15 + it * 2).toLong(), ChronoUnit.MINUTES), 50)
        }

        assertNull(HealthMetrics.estimateAwakenings(session, sparse))
    }

    @Test
    fun `a raised cluster counts once, not once per sample`() {
        val session = nightOfTheEleventh[1]
        val samples = (0 until 60).map { index ->
            val time = session.start.plus((15 + index * 2).toLong(), ChronoUnit.MINUTES)
            // Three consecutive readings well above the baseline, as a brief awakening looks at one
            // sample every two minutes.
            val bpm = if (index in 20..22) 58L else 47L
            HeartRateSample(time, bpm)
        }

        assertEquals(1, HealthMetrics.estimateAwakenings(session, samples))
    }

    @Test
    fun `separate clusters are counted separately`() {
        val session = nightOfTheEleventh[1]
        val samples = (0 until 60).map { index ->
            val time = session.start.plus((15 + index * 2).toLong(), ChronoUnit.MINUTES)
            val bpm = if (index in 10..11 || index in 40..41) 58L else 47L
            HeartRateSample(time, bpm)
        }

        assertEquals(2, HealthMetrics.estimateAwakenings(session, samples))
    }

    @Test
    fun `falling asleep and waking are not counted as awakenings`() {
        val session = SessionWindow(
            "n",
            Instant.parse("2026-09-11T00:00:00Z"),
            Instant.parse("2026-09-11T06:00:00Z"),
        )
        val samples = (0 until 89).map { index ->
            val time = session.start.plus((index * 4).toLong(), ChronoUnit.MINUTES)
            // Raised only inside the first and last ten minutes of the window.
            val settling = time.isBefore(session.start.plus(10, ChronoUnit.MINUTES))
            val waking = time.isAfter(session.end.minus(10, ChronoUnit.MINUTES))
            HeartRateSample(time, if (settling || waking) 62L else 47L)
        }

        assertEquals(0, HealthMetrics.estimateAwakenings(session, samples))
    }

    @Test
    fun `a settled night reports no awakenings`() {
        val session = nightOfTheEleventh[1]
        val samples = (0 until 60).map { index ->
            HeartRateSample(session.start.plus((15 + index * 2).toLong(), ChronoUnit.MINUTES), 47L)
        }

        assertEquals(0, HealthMetrics.estimateAwakenings(session, samples))
    }
}
