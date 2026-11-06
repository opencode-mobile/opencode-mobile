package dev.opencode.hexcode.session

import dev.opencode.hexcode.firstrun.ModelChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** How far opening today's session got. */
sealed interface SessionSetup {
    /** Finding or creating today's session. Silent: the status area and the top bar state are empty. */
    data object Opening : SessionSetup

    data class Ready(
        val sessionId: String,
    ) : SessionSetup

    /** "Couldn't open today's session" with Try again; [retrying] while Try again runs. */
    data class Failed(
        val retrying: Boolean = false,
    ) : SessionSetup
}

/** Everything home shows about today's session. */
data class TodayState(
    val setup: SessionSetup = SessionSetup.Opening,
    val summary: SummaryState = SummaryState(),
    val streamUp: Boolean = true,
    /** The oldest question or approval waiting, in the session or one of its subagents. */
    val pendingInput: PendingItem? = null,
    /** When the current run started, or null while the session is idle. */
    val busySince: Long? = null,
    /** Messages in the inbox, waiting for this turn to end. */
    val waiting: Int = 0,
    /** The same messages, oldest first, for the Waiting to send sheet. */
    val queued: List<InboxEntry> = emptyList(),
    /** Show the new-day notice; [TodaySession.newDayShown] clears it. */
    val newDayNotice: Boolean = false,
) {
    /** The top bar's state; null (an empty slot) until today's session is open. */
    val agent: AgentStatus?
        get() = if (setup is SessionSetup.Ready) AgentStatus.of(streamUp, pendingInput != null, busySince) else null
}

/** What the live session reads from the server besides setup; [OpenCodeTodayApi] in the app. */
interface LiveSessionApi {
    /** The state read after every (re)connect. */
    suspend fun snapshot(rootId: String): LiveSnapshot

    /** `POST /api/session/{id}/generate`, at most [SummaryRefresher.TIMEOUT_MS]. */
    suspend fun summarise(
        sessionId: String,
        prompt: String,
    ): String

    /** `POST /api/session/{id}/prompt` with [text] and [delivery] (`queue` or `steer`); returns the inbox item's id. */
    suspend fun send(
        sessionId: String,
        text: String,
        delivery: String,
    ): String

    /** `DELETE /api/session/{id}/inbox/{item}`: takes back a message that hasn't been delivered. */
    suspend fun cancelQueued(
        sessionId: String,
        itemId: String,
    )

    /** `POST /api/session/{id}/model`. */
    suspend fun switchModel(
        sessionId: String,
        model: ModelChoice,
    )
}

/**
 * Today's session for home (app-plan.md, The daily session and Today's session on screen). On open, on Try again, and
 * back in front on a new date it finds or creates today's session and puts the message rules ([DailySessionSetup]),
 * then opens the event stream. On every (re)connect it reads the state again and then refreshes the summary (the
 * saved card shows until then), and so does the end of a run. A session that never ran shows the empty card and asks
 * for nothing. Without a stream the summary is asked for on open.
 *
 * The sample sessions ([TodaySessionDemo]) run the same code with fakes behind [setup], [api] and [transport].
 */
class TodaySession(
    private val scope: CoroutineScope,
    private val setup: DailySessionSetup,
    private val api: LiveSessionApi,
    transport: EventTransport?,
    private val directory: () -> String,
    private val model: () -> ModelChoice?,
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
    summaryTimeoutMs: Long = SummaryRefresher.TIMEOUT_MS,
    /** Keeps the last card across launches. */
    summaryStore: SummaryStore? = null,
) {
    private val own = MutableStateFlow(TodayState())

    /** The session the summary is about; set once setup worked. */
    @Volatile
    private var sessionId: String? = null

    private val summary =
        SummaryRefresher(
            scope,
            SummarySource { prompt -> api.summarise(sessionId ?: error("No session open"), prompt) },
            clock,
            summaryTimeoutMs,
            busy = { live?.busySince != null },
            store = summaryStore,
            today = today,
        )

    private val stream = transport?.let { LiveEventStream(scope, it) }

    val state: StateFlow<TodayState> =
        combine(own, summary.state) { state, card -> state.copy(summary = card) }
            .stateIn(scope, SharingStarted.Eagerly, TodayState())

    private var live: LiveSessionState? = null

    /** The local date today's session was opened for. */
    private var day: LocalDate? = null
    private var opening: Job? = null
    private var backgroundStop: Job? = null

    init {
        stream?.onConnected = ::connected
        stream?.onDisconnected = { own.update { it.copy(streamUp = false) } }
        stream?.onEvent = ::received
    }

    /** Finds or creates today's session and follows it. A new session shows the empty card; one that ran, a summary. */
    fun open() {
        if (opening?.isActive == true) return
        opening = scope.launch { openNow() }
    }

    /** Try again, after "Couldn't open today's session". */
    fun retry() {
        if (own.value.setup == SessionSetup.Failed(retrying = false)) open()
    }

    /** ↻. */
    fun refresh() {
        if (own.value.setup is SessionSetup.Ready) summary.refresh()
    }

    fun newDayShown() = own.update { it.copy(newDayNotice = false) }

    /** Back in front: on a new date (or after a failed setup) open today's session again; else reconnect at once. */
    fun enteredForeground() {
        backgroundStop?.cancel()
        backgroundStop = null
        when (own.value.setup) {
            SessionSetup.Failed(retrying = false) -> open()
            is SessionSetup.Ready ->
                when {
                    day != today() -> open()
                    stream?.isRunning == false -> stream?.start()
                    else -> stream?.wake()
                }
            else -> Unit
        }
    }

    /** The stream closes [BACKGROUND_GRACE_MS] after the app goes to the background; what it misses is read back. */
    fun enteredBackground() {
        val stream = stream ?: return
        backgroundStop?.cancel()
        backgroundStop =
            scope.launch {
                delay(BACKGROUND_GRACE_MS)
                stream.stop()
            }
    }

    /** The network returned: reconnect at once. */
    fun networkReturned() {
        if (own.value.setup is SessionSetup.Ready && backgroundStop == null) stream?.wake()
    }

    /** Settings › Model changed: today's session uses the new model and level from now on. */
    fun switchModel(choice: ModelChoice) {
        val id = sessionId ?: return
        scope.launch {
            try {
                api.switchModel(id, choice)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The next open doesn't retry it; Settings › Model can be saved again.
            }
        }
    }

    /**
     * Sends [text] to today's session with [delivery]; returns the inbox item's id, which [cancelQueued] takes back.
     * Throws when the session isn't open or the server refuses it.
     */
    suspend fun send(
        text: String,
        delivery: String,
    ): String = api.send(sessionId ?: error("Today's session isn't open"), text, delivery)

    /** Takes back a message waiting in the inbox; once the DELETE works it leaves [TodayState.queued] at once. */
    suspend fun cancelQueued(itemId: String) {
        val id = sessionId ?: error("Today's session isn't open")
        api.cancelQueued(id, itemId)
        if (live?.rootId == id) {
            live = live?.withoutInbox(itemId)
            publish()
        }
    }

    /** Waits until the event stream is up, as after a reconnect. */
    suspend fun awaitStream() {
        state.first { it.streamUp }
    }

    /** Developer settings' "Drop event stream" (debug builds). */
    fun dropStream() {
        stream?.drop()
    }

    private suspend fun openNow() {
        own.update {
            it.copy(setup = if (it.setup is SessionSetup.Failed) SessionSetup.Failed(retrying = true) else SessionSetup.Opening)
        }
        stream?.stop()
        val date = today()
        val directory = directory()
        // The card saved for today's session in this project shows while setup runs.
        summary.preload(date, directory)
        when (val result = setup.open(date, directory, model())) {
            DailySessionResult.Failed -> own.update { it.copy(setup = SessionSetup.Failed()) }
            is DailySessionResult.Opened -> {
                val session = result.session
                day = date
                sessionId = session.id
                live = LiveSessionState(session.id, hasRun = session.busySince != null)
                summary.open(session.id, date, directory)
                publish(streamUp = true)
                own.update { it.copy(setup = SessionSetup.Ready(session.id), newDayNotice = result.newDay) }
                stream?.start()
                // With a stream, the first summary waits for its read-back, which says whether the agent is working
                // (an empty reply then isn't a failure); the saved card shows meanwhile. A session that never ran
                // shows its empty card now.
                if (stream == null || live?.hasRun == false) refreshSummary()
            }
        }
    }

    private suspend fun connected() {
        own.update { it.copy(streamUp = true) }
        val root = live?.rootId ?: return
        try {
            val snapshot = api.snapshot(root)
            if (live?.rootId == root) {
                live = live?.apply(snapshot)
                publish()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The stream is up; the next event or reconnect brings the rest.
        }
        refreshSummary()
    }

    private fun received(event: SessionEvent) {
        val current = live ?: return
        val (next, effect) = current.apply(event, clock())
        live = next
        publish()
        if (effect == LiveEffect.RunEnded) summary.refresh()
    }

    private fun publish(streamUp: Boolean? = null) {
        val state = live
        own.update {
            it.copy(
                streamUp = streamUp ?: it.streamUp,
                busySince = state?.busySince,
                pendingInput = state?.firstPending,
                waiting = state?.waiting ?: 0,
                queued = state?.queued.orEmpty(),
            )
        }
    }

    /** The empty card for a session that never ran; else a new summary. */
    private fun refreshSummary() {
        if (live?.hasRun == false) summary.showEmpty(day ?: today()) else summary.refresh()
    }

    companion object {
        /** The stream closes this long after the app goes to the background. */
        const val BACKGROUND_GRACE_MS = 5_000L
    }
}
