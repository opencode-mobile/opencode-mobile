package dev.opencode.hexcode.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A question or an approval the agent is waiting on. */
data class PendingInput(
    val kind: Kind,
    /** The question's text, or what the approval is for. */
    val text: String,
) {
    enum class Kind { Question, Approval }
}

/** How far opening today's session got. */
sealed interface SessionSetup {
    data object Opening : SessionSetup

    data class Ready(
        val sessionId: String,
    ) : SessionSetup

    /** "Couldn't open today's session", with Try again. */
    data object Failed : SessionSetup
}

/** Everything home shows about today's session. */
data class TodayState(
    val setup: SessionSetup = SessionSetup.Opening,
    val summary: SummaryState = SummaryState(),
    val streamUp: Boolean = true,
    val pendingInput: PendingInput? = null,
    /** When the current turn started, or null while the session is idle. */
    val busySince: Long? = null,
    /** Messages in the inbox, waiting for this turn to end. */
    val waiting: Int = 0,
    /** Show the new-day notice; [TodaySession.newDayShown] clears it. */
    val newDayNotice: Boolean = false,
) {
    val agent: AgentStatus get() = AgentStatus.of(streamUp, pendingInput != null, busySince)
}

/** The last date the app opened a session on, saved on the phone. */
interface DailySessionMemory {
    suspend fun lastOpenedOn(): LocalDate?

    suspend fun setLastOpenedOn(date: LocalDate)
}

/**
 * Today's session for home: opens it ([DailySessionSetup]), keeps its summary ([SummaryRefresher]) and the agent's
 * state. Round 1 fills the state from [TodaySessionDemo]; round 2 adds the event stream.
 */
class TodaySession(
    private val scope: CoroutineScope,
    private val setup: DailySessionSetup,
    private val memory: DailySessionMemory,
    private val summary: SummaryRefresher,
    private val directory: () -> String,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val own = MutableStateFlow(TodayState())

    val state: StateFlow<TodayState> =
        combine(own, summary.state) { state, card -> state.copy(summary = card) }
            .stateIn(scope, SharingStarted.Eagerly, TodayState())

    private var opening: Job? = null

    /** Finds or creates today's session, then shows its card: the empty one for a new session, else a summary. */
    fun open() {
        if (opening?.isActive == true) return
        own.update { it.copy(setup = SessionSetup.Opening) }
        opening =
            scope.launch {
                val date = today()
                when (val result = setup.open(date, directory(), memory.lastOpenedOn())) {
                    DailySessionResult.Failed -> own.update { it.copy(setup = SessionSetup.Failed) }
                    is DailySessionResult.Opened -> {
                        memory.setLastOpenedOn(date)
                        own.update { it.copy(setup = SessionSetup.Ready(result.sessionId), newDayNotice = result.newDay) }
                        if (result.created) summary.showEmpty(date) else summary.refresh()
                    }
                }
            }
    }

    /** Try again, after "Couldn't open today's session". */
    fun retry() = open()

    /** ↻. */
    fun refresh() {
        if (own.value.setup is SessionSetup.Ready) summary.refresh()
    }

    fun newDayShown() = own.update { it.copy(newDayNotice = false) }

    /** Sets what the event stream will report (round 2), and what the demos show. */
    fun updateAgent(change: (TodayState) -> TodayState) = own.update(change)
}
