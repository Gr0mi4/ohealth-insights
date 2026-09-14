package dev.gr0mi4.ohealthinsights

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of a sync, held outside any Activity.
 *
 * A sync used to run in the Activity's own scope, so it died with the Activity and stalled whenever
 * the phone dozed - a full history run failed exactly that way, two hours of sleep between one log
 * line and a sixty-second timeout. The work now belongs to [SyncService]; the screen only watches.
 */
object SyncSession {

    sealed interface State {
        data object Idle : State

        data class Running(
            val diagnostic: Boolean,
            val stage: String,
            val startedAtElapsedMillis: Long,
        ) : State

        /** Held until the screen picks it up, since saving an export needs an Activity. */
        data class Finished(
            val diagnostic: Boolean,
            val outcome: SyncOutcome,
            val startedAtElapsedMillis: Long,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean
        get() = _state.value is State.Running

    internal fun started(diagnostic: Boolean, stage: String, startedAtElapsedMillis: Long) {
        _state.value = State.Running(diagnostic, stage, startedAtElapsedMillis)
    }

    internal fun stage(stage: String) {
        val running = _state.value as? State.Running ?: return
        _state.value = running.copy(stage = stage)
    }

    internal fun finished(outcome: SyncOutcome) {
        val running = _state.value as? State.Running
        _state.value = State.Finished(
            diagnostic = running?.diagnostic ?: false,
            outcome = outcome,
            startedAtElapsedMillis = running?.startedAtElapsedMillis ?: 0L,
        )
    }

    /** Called once the screen has dealt with a finished sync, so it is not handled twice. */
    fun clearFinished() {
        if (_state.value is State.Finished) _state.value = State.Idle
    }
}
