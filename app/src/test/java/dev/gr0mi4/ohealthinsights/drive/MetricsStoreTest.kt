package dev.gr0mi4.ohealthinsights.drive

import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetricsStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var store: MetricsStore

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        file = File(temporaryFolder.root, MetricsStore.FILE_NAME)
        store = MetricsStore(file)
    }

    private fun workout(id: String, kcal: Double?) = WorkoutMetric(
        sessionId = id,
        title = "Outdoor run",
        startTime = Instant.parse("2026-09-13T18:31:00Z"),
        endTime = Instant.parse("2026-09-13T18:52:00Z"),
        caloriesKcal = kcal,
    )

    @Test
    fun `re-exporting a day does not grow the workout count`() {
        repeat(3) { store.addWorkout(today, workout("session-a", 162.0)) }
        store.addWorkout(today, workout("session-b", 200.0))

        val day = store.recentDays(1).single()
        assertEquals(2, day.workoutCount)
        assertEquals(362.0, day.workoutCaloriesKcal!!, 0.001)
    }

    @Test
    fun `re-exporting a night does not grow the time in bed`() {
        repeat(4) { store.addSleepSession(today, "night-1", 425, awakenings = 2) }

        val day = store.recentDays(1).single()
        assertEquals(425L, day.sleepMinutes)
        assertEquals(2, day.awakenings)
    }

    @Test
    fun `a workout with no calories still counts as a workout`() {
        store.addWorkout(today, workout("session-a", null))

        val day = store.recentDays(1).single()
        assertEquals(1, day.workoutCount)
        assertNull(day.workoutCaloriesKcal)
    }

    @Test
    fun `daily figures survive a round trip through the file`() {
        store.upsertDaily(
            DailyMetric(
                date = today,
                stepsTotal = 15_315,
                stepsOHealth = 12_000,
                caloriesOHealthKcal = 820.0,
                caloriesCoveredMinutes = 385,
            ),
        )

        val reopened = MetricsStore(file).recentDays(1).single()
        assertEquals(15_315L, reopened.stepsTotal)
        assertEquals(12_000L, reopened.stepsOHealth)
        assertEquals(820.0, reopened.caloriesOHealthKcal!!, 0.001)
        assertEquals(385L, reopened.caloriesCoveredMinutes)
    }

    @Test
    fun `upsert keeps session-keyed figures the daily row knows nothing about`() {
        store.addWorkout(today, workout("session-a", 162.0))
        store.addSleepSession(today, "night-1", 425, awakenings = 1)

        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))

        val day = store.recentDays(1).single()
        assertEquals(1, day.workoutCount)
        assertEquals(425L, day.sleepMinutes)
        assertEquals(12_000L, day.stepsOHealth)
    }

    @Test
    fun `upsert does not erase values it was given nothing for`() {
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0))
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))

        val day = store.recentDays(1).single()
        assertEquals(820.0, day.caloriesOHealthKcal!!, 0.001)
        assertEquals(12_000L, day.stepsOHealth)
    }

    @Test
    fun `a batch writes nothing until it is committed`() {
        store.beginBatch()
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))
        store.addWorkout(today, workout("session-a", 162.0))

        assertFalse("nothing should be on disk yet", file.exists())
        assertEquals(12_000L, store.recentDays(1).single().stepsOHealth)

        store.commitBatch()

        assertTrue(file.exists())
        assertEquals(12_000L, MetricsStore(file).recentDays(1).single().stepsOHealth)
    }

    @Test
    fun `a batch sees what was already on disk`() {
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0))

        store.beginBatch()
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))
        store.commitBatch()

        val day = MetricsStore(file).recentDays(1).single()
        assertEquals(820.0, day.caloriesOHealthKcal!!, 0.001)
        assertEquals(12_000L, day.stepsOHealth)
    }

    @Test
    fun `a truncated file reads as empty rather than throwing`() {
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))
        file.writeText(file.readText().take(20))

        assertEquals(emptyList<DailyMetric>(), MetricsStore(file).recentDays(1))
    }

    @Test
    fun `no temporary file is left behind after a write`() {
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))

        val leftovers = temporaryFolder.root.listFiles()?.map { it.name }.orEmpty()
        assertTrue("unexpected leftovers: $leftovers", leftovers.none { it.endsWith(".tmp") })
        assertTrue(leftovers.contains(MetricsStore.FILE_NAME))
    }

    @Test
    fun `days past the retention window are dropped`() {
        val old = today.minusDays(MetricsStore.RETENTION_DAYS + 5L)
        store.upsertDaily(DailyMetric(date = old, stepsOHealth = 1))
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 2))

        val dates = MetricsStore(file).allMetrics().map { it.date }
        assertEquals(listOf(today), dates)
    }

    @Test
    fun `a year of history is kept, not a quarter`() {
        listOf(0L, 120L, 300L, 400L).forEach { back ->
            store.upsertDaily(DailyMetric(date = today.minusDays(back), stepsOHealth = back))
        }

        assertEquals(4, MetricsStore(file).allMetrics().size)
    }

    @Test
    fun `replacing a value is recorded with what it was`() {
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0))
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 37.0))

        val change = MetricsStore(file).changeLog().single()
        assertEquals(today, change.date)
        assertEquals("caloriesOHealthKcal", change.field)
        assertEquals("820.0", change.before)
        assertEquals("37.0", change.after)
    }

    @Test
    fun `filling in a blank is not a change`() {
        store.upsertDaily(DailyMetric(date = today, stepsOHealth = 12_000))
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0))

        assertEquals(emptyList<MetricChange>(), MetricsStore(file).changeLog())
    }

    @Test
    fun `writing the same value again is not a change`() {
        repeat(3) { store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0)) }

        assertEquals(emptyList<MetricChange>(), MetricsStore(file).changeLog())
    }

    @Test
    fun `changes made inside a batch are written when it commits`() {
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 820.0))

        store.beginBatch()
        store.upsertDaily(DailyMetric(date = today, caloriesOHealthKcal = 37.0))
        assertEquals(emptyList<MetricChange>(), MetricsStore(file).changeLog())

        store.commitBatch()
        assertEquals(1, MetricsStore(file).changeLog().size)
    }

    @Test
    fun `a file written before calories were renamed still reads`() {
        // 0.6.0 wrote activeCaloriesOHealthKcal; the value is the same measurement.
        file.writeText(
            """{"days":[{"date":"$today","activeCaloriesOHealthKcal":820.0,"stepsOHealth":12000}]}""",
        )

        val day = MetricsStore(file).recentDays(1).single()
        assertEquals(820.0, day.caloriesOHealthKcal!!, 0.001)
        assertEquals(12_000L, day.stepsOHealth)
    }
}
