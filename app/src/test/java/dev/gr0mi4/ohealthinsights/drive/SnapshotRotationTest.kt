package dev.gr0mi4.ohealthinsights.drive

import dev.gr0mi4.ohealthinsights.drive.SnapshotRotation.Action
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotRotationTest {

    private val now: Instant = Instant.parse("2026-09-14T10:00:00Z")

    private fun daysAgo(days: Long): Instant = now.minus(days, ChronoUnit.DAYS)

    private fun plan(
        daily: Instant? = null,
        weekly: Instant? = null,
        monthly: Instant? = null,
    ): List<Action> = SnapshotRotation.plan(
        now = now,
        modifiedAt = mapOf(
            SnapshotRotation.DAILY to daily,
            SnapshotRotation.WEEKLY to weekly,
            SnapshotRotation.MONTHLY to monthly,
        ),
    )

    @Test
    fun `the first sync writes only the youngest generation`() {
        assertEquals(listOf(Action.WriteCurrent(SnapshotRotation.DAILY)), plan())
    }

    @Test
    fun `a second sync on the same day changes nothing once every generation exists`() {
        assertEquals(
            emptyList<Action>(),
            plan(daily = now.minus(3, ChronoUnit.HOURS), weekly = daysAgo(2), monthly = daysAgo(10)),
        )
    }

    @Test
    fun `a generation that does not exist yet is seeded from the one below it`() {
        // The youngest is fresh, so it is not rewritten - but there is no weekly copy to age into.
        assertEquals(
            listOf(Action.Promote(from = SnapshotRotation.DAILY, to = SnapshotRotation.WEEKLY)),
            plan(daily = now.minus(3, ChronoUnit.HOURS)),
        )
    }

    @Test
    fun `this is the point - a bad write cannot reach all three in three syncs`() {
        // Three syncs in one day, each with a fresh daily snapshot already in place.
        repeat(3) {
            assertEquals(emptyList<Action>(), plan(daily = now, weekly = daysAgo(2), monthly = daysAgo(10)))
        }
    }

    @Test
    fun `after a day the youngest is rewritten and nothing is promoted yet`() {
        assertEquals(
            listOf(Action.WriteCurrent(SnapshotRotation.DAILY)),
            plan(daily = daysAgo(1), weekly = daysAgo(3), monthly = daysAgo(10)),
        )
    }

    @Test
    fun `after a week the daily is promoted before it is overwritten`() {
        val actions = plan(daily = daysAgo(1), weekly = daysAgo(7), monthly = daysAgo(10))

        assertEquals(
            listOf(
                Action.Promote(from = SnapshotRotation.DAILY, to = SnapshotRotation.WEEKLY),
                Action.WriteCurrent(SnapshotRotation.DAILY),
            ),
            actions,
        )
    }

    @Test
    fun `after a month all three generations move, oldest first`() {
        val actions = plan(daily = daysAgo(1), weekly = daysAgo(8), monthly = daysAgo(31))

        assertEquals(
            listOf(
                Action.Promote(from = SnapshotRotation.WEEKLY, to = SnapshotRotation.MONTHLY),
                Action.Promote(from = SnapshotRotation.DAILY, to = SnapshotRotation.WEEKLY),
                Action.WriteCurrent(SnapshotRotation.DAILY),
            ),
            actions,
        )
    }

    @Test
    fun `a generation that does not exist yet is not promoted from`() {
        // Nothing to copy into monthly while weekly has never been written.
        val actions = plan(daily = daysAgo(1), weekly = null, monthly = null)

        assertEquals(
            listOf(
                Action.Promote(from = SnapshotRotation.DAILY, to = SnapshotRotation.WEEKLY),
                Action.WriteCurrent(SnapshotRotation.DAILY),
            ),
            actions,
        )
    }

    @Test
    fun `a missing generation is treated as overdue`() {
        val actions = plan(daily = now, weekly = daysAgo(1), monthly = null)

        assertEquals(listOf(Action.Promote(from = SnapshotRotation.WEEKLY, to = SnapshotRotation.MONTHLY)), actions)
    }

    @Test
    fun `promotion never reads a generation that the same plan has already overwritten`() {
        val actions = plan(daily = daysAgo(2), weekly = daysAgo(9), monthly = daysAgo(40))

        // Each source appears before the action that replaces it.
        val writtenTo = mutableSetOf<String>()
        actions.forEach { action ->
            when (action) {
                is Action.Promote -> {
                    assertEquals("$action reads a generation already replaced", false, action.from in writtenTo)
                    writtenTo += action.to
                }

                is Action.WriteCurrent -> writtenTo += action.to
            }
        }
    }

    @Test
    fun `an hour before the threshold is not yet due`() {
        val almost = now.minus(23, ChronoUnit.HOURS)
        assertEquals(emptyList<Action>(), plan(daily = almost, weekly = daysAgo(1), monthly = daysAgo(1)))
    }
}
