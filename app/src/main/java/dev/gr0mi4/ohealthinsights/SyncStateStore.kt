package dev.gr0mi4.ohealthinsights

import android.content.Context
import java.time.Instant
import java.time.LocalDate

/**
 * Incremental sync bookkeeping. Lives outside the UI so the foreground screen and the background
 * worker read and advance the same checkpoint.
 */
class SyncStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun changesToken(): String? = prefs.getString(KEY_CHANGES_TOKEN, null)

    fun lastSuccessfulExport(): Instant? = prefs.getString(KEY_LAST_EXPORT, null)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun hasCheckpoint(): Boolean = !prefs.getString(KEY_LAST_EXPORT, null).isNullOrBlank()

    fun hasChangesToken(): Boolean = !prefs.getString(KEY_CHANGES_TOKEN, null).isNullOrBlank()

    fun historyStartDate(): LocalDate = prefs.getString(KEY_HISTORY_START, null)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: HealthExportEngine.defaultHistoryStartDate

    /** Commits synchronously: a lost checkpoint turns the next sync into a silent data gap. */
    fun persistCheckpoint(checkpoint: SyncCheckpoint): Boolean = prefs.edit()
        .putString(KEY_CHANGES_TOKEN, checkpoint.changesToken)
        .putString(KEY_LAST_EXPORT, checkpoint.exportedAt.toString())
        .commit()

    /** Clears the checkpoint so the next sync rebuilds the selected period from scratch. */
    fun setHistoryStartDate(date: LocalDate): Boolean = prefs.edit()
        .putString(KEY_HISTORY_START, date.toString())
        .remove(KEY_CHANGES_TOKEN)
        .remove(KEY_LAST_EXPORT)
        .commit()

    companion object {
        private const val PREFS_NAME = "ohealth_sync_state"
        private const val KEY_CHANGES_TOKEN = "changes_token_v1"
        private const val KEY_LAST_EXPORT = "last_successful_export_v1"
        private const val KEY_HISTORY_START = "history_start_date_v1"

        val requestedPermissions = HealthExportEngine.recordReadPermissions +
            HealthExportEngine.historyPermission +
            HealthExportEngine.backgroundPermission
    }
}

data class SyncCheckpoint(
    val changesToken: String?,
    val exportedAt: Instant,
)
