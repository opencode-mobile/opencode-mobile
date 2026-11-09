package dev.opencode.mobile.ui.sessionchat.region

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.event.EventStreamConnectionState
import dev.opencode.mobile.domain.goal.SessionGoal
import dev.opencode.mobile.domain.goal.SessionGoalAction
import dev.opencode.mobile.domain.goal.SessionGoalDomainAdapter
import dev.opencode.mobile.domain.goal.SessionGoalRepository
import dev.opencode.mobile.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SessionChatGoalRegionState(
    /** The session's goal; null without one, on v1, or on a server without the goal plugin. */
    val goal: SessionGoal? = null,
    /** The event stream dropped: [goal] is the last known one, and its actions are off. */
    val isStale: Boolean = false,
    /** Pause, resume or clear in flight, until the goal-changed event or the timeout. */
    val pending: SessionGoalAction? = null,
    val sheetOpen: Boolean = false,
    /** The sheet shows the whole objective instead of five lines. */
    val objectiveExpanded: Boolean = false,
    /** A failed action's message, shown once as a snackbar. */
    val notice: UiText? = null,
)

/**
 * The open session's `/goal` (#1007): read through the goal RPC, kept live from
 * `rpc.opencode.goal.changed`, refetched on reconnect and when the session opens again
 * (changes while away are missed). A failing read (v2 without the plugin) shows no goal and
 * retries quietly after [retryDelaysMs].
 */
class SessionChatGoal(
    private val repository: SessionGoalRepository,
    private val connectionState: StateFlow<EventStreamConnectionState>,
    private val scope: CoroutineScope,
) {
    internal var retryDelaysMs: List<Long> = RETRY_DELAYS_MS
    internal var commandTimeoutMs: Long = COMMAND_TIMEOUT_MS

    private val _state = MutableStateFlow(SessionChatGoalRegionState())
    val state: StateFlow<SessionChatGoalRegionState> = _state.asStateFlow()

    private var sessionId: String? = null
    private var directory: String? = null
    private var bindJob: Job? = null
    private var fetchJob: Job? = null
    private var commandJob: Job? = null

    /** Completes on the next goal change for the bound session; a command waits on it. */
    private var changeSignal: CompletableDeferred<Unit>? = null

    // A change that lands while a fetch is in flight is newer than its reply, so each one takes a turn.
    private var turn = 0

    fun bind(
        sessionId: String,
        directory: String,
    ) {
        if (this.sessionId == sessionId) {
            this.directory = directory
            return
        }
        reset()
        this.sessionId = sessionId
        this.directory = directory
        bindJob =
            scope.launch {
                if (!runCatching { repository.isAvailable() }.getOrDefault(false)) return@launch
                launch {
                    repository.changes().collect { change ->
                        if (change.sessionId == this@SessionChatGoal.sessionId) onChanged(change.goal)
                    }
                }
                launch { followConnection() }
                fetch()
            }
    }

    /** The session moved to another directory; the goal stays, later reads use [directory]. */
    fun moveDirectory(directory: String) {
        this.directory = directory
    }

    /** Session Chat opened this session again: changes while it was away were missed. */
    fun refresh() {
        if (bindJob?.isActive != true) return
        fetch()
    }

    fun reset() {
        bindJob?.cancel()
        fetchJob?.cancel()
        commandJob?.cancel()
        bindJob = null
        fetchJob = null
        commandJob = null
        changeSignal = null
        sessionId = null
        directory = null
        turn += 1
        _state.value = SessionChatGoalRegionState()
    }

    fun openSheet() {
        if (_state.value.goal == null) return
        _state.update { it.copy(sheetOpen = true) }
    }

    fun closeSheet() {
        _state.update { it.copy(sheetOpen = false, objectiveExpanded = false) }
    }

    fun toggleObjective() {
        _state.update { it.copy(objectiveExpanded = !it.objectiveExpanded) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Sends `/goal pause|resume|clear`; one at a time, never while the goal is stale. */
    fun send(action: SessionGoalAction) {
        val id = sessionId ?: return
        val current = _state.value
        if (current.goal == null || current.pending != null || current.isStale) return
        val signal = CompletableDeferred<Unit>()
        changeSignal = signal
        _state.update { it.copy(pending = action, notice = null) }
        commandJob =
            scope.launch {
                try {
                    repository.send(id, action)
                    // The button shows progress until the change lands; the timeout frees it.
                    val changed = withTimeoutOrNull(commandTimeoutMs) { signal.await() }
                    finishCommand()
                    if (changed == null) fetch()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    finishCommand()
                    _state.update { it.copy(notice = failureNotice(action, SessionGoalDomainAdapter.errorMessage(error))) }
                    fetch()
                }
            }
    }

    private fun finishCommand() {
        changeSignal = null
        _state.update { it.copy(pending = null) }
    }

    private fun onChanged(goal: SessionGoal?) {
        turn += 1
        apply(goal)
        changeSignal?.complete(Unit)
    }

    private fun apply(goal: SessionGoal?) {
        _state.update { state ->
            state.copy(
                goal = goal,
                isStale = false,
                sheetOpen = state.sheetOpen && goal != null,
                objectiveExpanded = state.objectiveExpanded && goal?.id == state.goal?.id,
            )
        }
    }

    private suspend fun followConnection() {
        var wasConnected = connectionState.value == EventStreamConnectionState.Connected
        connectionState.collect { connection ->
            val connected = connection == EventStreamConnectionState.Connected
            when {
                connected && !wasConnected -> fetch()
                !connected && wasConnected -> _state.update { it.copy(isStale = it.goal != null) }
            }
            wasConnected = connected
        }
    }

    private fun fetch() {
        val id = sessionId ?: return
        fetchJob?.cancel()
        turn += 1
        val fetchTurn = turn
        fetchJob =
            scope.launch {
                var attempt = 0
                while (true) {
                    try {
                        val goal = repository.goal(id, directory)
                        if (turn == fetchTurn && sessionId == id) apply(goal)
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        // A server without the goal plugin answers with an error: no goal, no error UI.
                        if (turn == fetchTurn && sessionId == id) apply(null)
                        val wait = retryDelaysMs.getOrNull(attempt) ?: return@launch
                        attempt += 1
                        delay(wait)
                        if (turn != fetchTurn) return@launch
                    }
                }
            }
    }

    private fun failureNotice(
        action: SessionGoalAction,
        message: String?,
    ): UiText {
        val (plain, withReason) =
            when (action) {
                SessionGoalAction.Pause -> R.string.session_chat_goal_pause_failed to R.string.session_chat_goal_pause_failed_reason
                SessionGoalAction.Resume -> R.string.session_chat_goal_resume_failed to R.string.session_chat_goal_resume_failed_reason
                SessionGoalAction.Clear -> R.string.session_chat_goal_clear_failed to R.string.session_chat_goal_clear_failed_reason
            }
        return if (message != null) UiText.resource(withReason, message) else UiText.resource(plain)
    }

    companion object {
        /** Waits before the 1st, 2nd and 3rd refetch of a failed read; then no goal until a change or reconnect. */
        val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

        /** How long pause, resume or clear may take before its button frees up again. */
        const val COMMAND_TIMEOUT_MS = 15_000L
    }
}
