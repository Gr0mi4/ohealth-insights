package dev.gr0mi4.ohealthinsights.drive

import java.time.Duration
import java.time.Instant

/**
 * Decides which metrics snapshots to refresh, separately from talking to Drive.
 *
 * The scheme is grandfather-father-son: three generations aged roughly a day, a week and a month.
 * Refreshing by age rather than on every sync is the whole point - snapshotting each time would copy
 * a bad write into all three within three syncs, while ageing them out leaves the monthly copy
 * predating anything noticed within a month.
 *
 * Kept as a pure plan so the date arithmetic can be exercised. A mistake here is silent: the
 * generations quietly stop rotating, or collapse onto each other, and it only shows when they are
 * needed.
 */
internal object SnapshotRotation {

    const val DAILY = "ohealth-metrics-daily.csv"
    const val WEEKLY = "ohealth-metrics-weekly.csv"
    const val MONTHLY = "ohealth-metrics-monthly.csv"

    private const val DAILY_MAX_AGE_DAYS = 1L
    private const val WEEKLY_MAX_AGE_DAYS = 7L
    private const val MONTHLY_MAX_AGE_DAYS = 30L

    val slots = listOf(DAILY, WEEKLY, MONTHLY)

    sealed interface Action {
        /** Copy one generation onto the next, replacing what that generation held. */
        data class Promote(val from: String, val to: String) : Action

        /** Write the series as it stands now into the youngest generation. */
        data class WriteCurrent(val to: String) : Action
    }

    /**
     * [modifiedAt] carries each snapshot's last-modified time, or null when it does not exist yet.
     *
     * Actions come back oldest generation first, so a promotion reads a generation before the step
     * that overwrites it.
     */
    fun plan(now: Instant, modifiedAt: Map<String, Instant?>): List<Action> {
        fun due(slot: String, maxAgeDays: Long): Boolean {
            val modified = modifiedAt[slot] ?: return true
            return Duration.between(modified, now).toDays() >= maxAgeDays
        }

        val actions = mutableListOf<Action>()
        if (due(MONTHLY, MONTHLY_MAX_AGE_DAYS) && modifiedAt[WEEKLY] != null) {
            actions += Action.Promote(from = WEEKLY, to = MONTHLY)
        }
        if (due(WEEKLY, WEEKLY_MAX_AGE_DAYS) && modifiedAt[DAILY] != null) {
            actions += Action.Promote(from = DAILY, to = WEEKLY)
        }
        if (due(DAILY, DAILY_MAX_AGE_DAYS)) {
            actions += Action.WriteCurrent(to = DAILY)
        }
        return actions
    }
}
