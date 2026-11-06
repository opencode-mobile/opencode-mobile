package dev.opencode.hexcode.session

import android.util.Log
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
import kotlinx.serialization.json.JsonObject
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
    /** The agent's to-do list; empty when it keeps none (no row). */
    val todos: List<TodoItem> = emptyList(),
    /** Subagents running now. */
    val subagentsRunning: Int = 0,
    /** When each agent shell running now started. */
    val shellStarts: List<Long> = emptyList(),
    /**
     * The message just delivered to the agent, for the "You: …" line in place of Next (app-plan.md, Just sent), until
     * the card made after that run ends.
     */
    val you: String? = null,
    /** Every question and approval waiting, oldest first: one queue for the sheets and Details' Needs you. */
    val pending: List<PendingItem> = emptyList(),
    /** Today's session's id once it's open. */
    val rootId: String? = null,
    /** Today's subagents, by id: their tasks and agents. */
    val children: Map<String, ChildInfo> = emptyMap(),
    /** The subagents running now. */
    val runningChildren: List<String> = emptyList(),
    /** The agent shells running now, with their commands. */
    val shells: List<ShellRun> = emptyList(),
    /** Every message in the inbox, queued or steering, oldest first, for Details' Waiting to send. */
    val inbox: List<InboxEntry> = emptyList(),
    /** The session's project; `global` has no Always. Null until it's read. */
    val projectId: String? = null,
    /** Goes up with each tool today's session finishes: Details reads Recent again. */
    val toolEvents: Int = 0,
) {
    /** What runs besides the session at [now], for "N running · Details ›": subagents, and shells after 10 s. */
    fun running(now: Long): Int = subagentsRunning + shellStarts.count { now - it >= SHELL_SHOWN_MS }

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

    /**
     * `POST /api/session/{id}/prompt` with [text], [delivery] (`queue` or `steer`) and [images] in the same request;
     * returns the inbox item's id.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        delivery: String,
        images: List<PromptImage> = emptyList(),
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

    /** The session's `time.updated` (`GET /api/session/{id}`), the summary card's basis; null when it isn't known. */
    suspend fun updatedAt(sessionId: String): Long? = null

    /**
     * `POST /api/session/{id}/permission/{request}/reply` with [decision] and, for a reject, [message]. Throws
     * [RequestGoneException] when the request is no longer there (`404`).
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: ApprovalDecision,
        message: String?,
    ): Unit = throw UnsupportedOperationException("No approvals here")

    /** `POST /api/session/{id}/form/{form}/reply` with `{"answer": …}`; [RequestGoneException] on `404` or `409`. */
    suspend fun replyForm(
        sessionId: String,
        formId: String,
        answer: JsonObject,
    ): Unit = throw UnsupportedOperationException("No questions here")

    /** `DELETE /api/session/{id}/form/{form}?message=…`; [RequestGoneException] on `404` or `409`. */
    suspend fun dismissForm(
        sessionId: String,
        formId: String,
        message: String,
    ): Unit = throw UnsupportedOperationException("No questions here")

    /** `POST /api/session/{id}/interrupt`: true when a run was stopped. */
    suspend fun interrupt(sessionId: String): Boolean = throw UnsupportedOperationException("No stop here")

    /** The sessions running now (`GET /api/session/active`), any project's. */
    suspend fun activeSessions(): Set<String> = emptySet()

    /** Running now's read: today's subagents, which of them run, and the agent shells of [rootId] and its subagents. */
    suspend fun running(
        rootId: String,
        directory: String,
    ): RunningRead = RunningRead(emptyMap(), emptySet(), emptyList())

    /** Recent's steps, from the session's newest assistant messages. */
    suspend fun recent(sessionId: String): List<RecentStep> = emptyList()
}

/**
 * The approval or question is no longer there: answered elsewhere, or its run ended (`404`, or `409
 * FormAlreadySettledError`). The sheet closes with "No longer needed".
 */
class RequestGoneException(
    message: String? = null,
) : Exception(message)

/** How Stop went (app-plan.md, Stop › Notice). */
sealed interface StopOutcome {
    /** A run was stopped; [waiting] when messages are still queued, which send after the next one. */
    data class Stopped(
        val waiting: Boolean,
    ) : StopOutcome

    /** Nothing was running: the agent had already finished. */
    data object NothingToStop : StopOutcome

    /** The interrupt didn't reach the server. */
    data object Failed : StopOutcome
}

/**
 * Today's session for home (app-plan.md, The daily session and Today's session on screen). On open, on Try again, and
 * back in front on a new date it finds or creates today's session and puts the message rules ([DailySessionSetup]),
 * then opens the event stream. On every (re)connect it reads the state again; then, with the session idle, it refreshes
 * the summary unless nothing is new since the saved card (app-plan.md, Nothing new). While the session is busy only the
 * run's end (and ↻) asks. A session that never ran shows the empty card and asks for nothing. Without a stream the
 * summary is asked for on open.
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
            object : SummarySource {
                override suspend fun summarise(prompt: String) = api.summarise(sessionId ?: error("No session open"), prompt)

                override suspend fun updatedAt() = sessionId?.let { api.updatedAt(it) }
            },
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

    /** What the phone sent, by inbox item, for the You line when it's delivered. */
    private val sent = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The message the You line shows, and whether its run has ended (the next card made while idle clears it). */
    private var you: String? = null
    private var youRunEnded = false

    /** The local date today's session was opened for. */
    private var day: LocalDate? = null
    private var opening: Job? = null
    private var backgroundStop: Job? = null

    init {
        summary.onNewCard = { busy ->
            if (!busy && youRunEnded) {
                you = null
                youRunEnded = false
                publish()
            }
        }
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
                // A switch moves `time.updated` but not what the card says: its basis moves with it.
                summary.moveBasis(api.updatedAt(id))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The next open doesn't retry it; Settings › Model can be saved again.
            }
        }
    }

    /**
     * Sends [text] with [images] to today's session with [delivery]; returns the inbox item's id, which [cancelQueued]
     * takes back. Throws when the session isn't open or the server refuses it.
     */
    suspend fun send(
        text: String,
        delivery: String,
        images: List<PromptImage> = emptyList(),
    ): String = api.send(sessionId ?: error("Today's session isn't open"), text, delivery, images).also { sent[it] = text }

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

    /**
     * Answers approval [item] on its own session's route (a subagent's child id): [ApprovalDecision.Reject] always
     * with a note, [note] when one was said, else [SessionPrompts.DENY_NOTE], so the agent carries on another way. Once
     * it worked the item leaves the queue; a reject takes the session's other approvals with it, as the server does.
     * Throws [RequestGoneException] when it was no longer there (and it leaves the queue too), or the server's error.
     */
    suspend fun reply(
        item: PendingItem,
        decision: ApprovalDecision,
        note: String? = null,
    ) {
        val message = if (decision == ApprovalDecision.Reject) note?.trim()?.ifEmpty { null } ?: SessionPrompts.DENY_NOTE else null
        settle(item) { api.replyPermission(item.sessionId, item.id, decision, message) }
        if (decision == ApprovalDecision.Reject) changeLive { it.withoutApprovals(item.sessionId) }
    }

    /** Answers question [item] with [answer] (`{"q0": "Red", "q1": ["Apple"]}`); as [reply] otherwise. */
    suspend fun answer(
        item: PendingItem,
        answer: JsonObject,
    ) = settle(item) { api.replyForm(item.sessionId, item.id, answer) }

    /** Dismisses question [item] with [SessionPrompts.DISMISS_NOTE], so the run goes on; as [reply] otherwise. */
    suspend fun dismiss(item: PendingItem) = settle(item) { api.dismissForm(item.sessionId, item.id, SessionPrompts.DISMISS_NOTE) }

    private suspend fun settle(
        item: PendingItem,
        call: suspend () -> Unit,
    ) {
        try {
            call()
        } catch (gone: RequestGoneException) {
            changeLive { it.withoutPending(item.id) }
            throw gone
        }
        changeLive { it.withoutPending(item.id) }
    }

    private fun changeLive(change: (LiveSessionState) -> LiveSessionState) {
        live = live?.let(change)
        publish()
    }

    /** Watches for a subagent's note waking today's session just after Stop (app-plan.md, Stop › At 3 s). */
    private class StopWatch(
        val root: String,
        val until: Long,
    ) {
        var noteSeen = false
    }

    @Volatile
    private var stopWatch: StopWatch? = null

    /**
     * Stop, after the 3 s hold (app-plan.md, Stop › At 3 s): interrupts today's session, then each of its subagents
     * that is running ([LiveSessionApi.activeSessions]). For [STOP_RETRY_MS] after, if a subagent's `<subagent …>` note
     * starts the session again, it's interrupted once more. Background shells are left running.
     */
    suspend fun stop(): StopOutcome {
        val root = sessionId ?: return StopOutcome.Failed
        val stopped =
            try {
                api.interrupt(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(STOP_TAG, "couldn't stop $root: ${error.message}")
                return StopOutcome.Failed
            }
        stopWatch = StopWatch(root, clock() + STOP_RETRY_MS)
        val children = live?.children.orEmpty()
        val active =
            if (children.isEmpty()) {
                emptySet()
            } else {
                try {
                    api.activeSessions()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(STOP_TAG, "couldn't read the running subagents: ${error.message}")
                    // The ones known to run are stopped anyway.
                    live?.runningChildren.orEmpty()
                }
            }
        var child = false
        for (id in children.filter { it in active }.sorted()) {
            try {
                child = api.interrupt(id) || child
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(STOP_TAG, "couldn't stop subagent $id: ${error.message}")
            }
        }
        Log.i(STOP_TAG, "stopped $root: interrupted=$stopped, subagents ${children.filter { it in active }}")
        return if (stopped || child) StopOutcome.Stopped(waiting = (live?.waiting ?: 0) > 0) else StopOutcome.NothingToStop
    }

    /** The one retry after Stop: a subagent's note woke today's session within [STOP_RETRY_MS]. */
    private fun watchStop(event: SessionEvent) {
        val watch = stopWatch ?: return
        if (clock() > watch.until) {
            stopWatch = null
            return
        }
        when {
            event is SessionEvent.InboxEnqueued &&
                event.sessionId == watch.root &&
                event.synthetic &&
                event.text.trimStart().startsWith("<subagent") -> watch.noteSeen = true
            event is SessionEvent.ExecutionStarted && event.sessionId == watch.root && watch.noteSeen -> {
                stopWatch = null
                Log.i(STOP_TAG, "a subagent's note started ${watch.root} again: interrupting it once more")
                scope.launch {
                    try {
                        api.interrupt(watch.root)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(STOP_TAG, "the second stop failed: ${error.message}")
                    }
                }
            }
        }
    }

    /** Details opened, or reconnected while open: reads Running now (subagents and agent shells) again. */
    suspend fun readRunning() {
        val root = sessionId ?: return
        val read = api.running(root, directory())
        if (live?.rootId == root) changeLive { it.apply(read) }
    }

    /** Details' Recent: the session's latest edits, commands and subagent starts. */
    suspend fun recent(): List<RecentStep> = api.recent(sessionId ?: return emptyList())

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
                you = null
                youRunEnded = false
                sent.clear()
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
        var updatedAt: Long? = null
        try {
            val snapshot = api.snapshot(root)
            if (live?.rootId == root) {
                live = live?.apply(snapshot)
                updatedAt = snapshot.updatedAt
                publish()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The stream is up; the next event or reconnect brings the rest. Without the read-back nothing says the
            // card is current, so it's asked for.
            if (live?.hasRun == false) summary.showEmpty(day ?: today()) else summary.refresh()
            return
        }
        refreshSummary(updatedAt)
    }

    private fun received(event: SessionEvent) {
        val current = live ?: return
        if (event is SessionEvent.InboxRemoved && event.delivered && event.sessionId == current.rootId) {
            // "You: …" from now until the card made after this run ends; a later message replaces it.
            val text =
                current.inbox
                    .firstOrNull { it.id == event.id }
                    ?.text
                    ?.ifEmpty { null } ?: sent[event.id]
            if (text != null) {
                you = text
                youRunEnded = false
            }
        }
        watchStop(event)
        val (next, effect) = current.apply(event, clock())
        live = next
        if (effect == LiveEffect.RunEnded && you != null) youRunEnded = true
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
                todos = state?.todos.orEmpty(),
                you = you,
                pending = state?.pending.orEmpty(),
                rootId = state?.rootId,
                children = state?.childInfo.orEmpty(),
                runningChildren = state?.runningChildren?.toList().orEmpty(),
                shells = state?.shellRuns.orEmpty(),
                inbox = state?.inbox.orEmpty(),
                projectId = state?.projectId,
                toolEvents = state?.toolEvents ?: 0,
                subagentsRunning = state?.runningChildren?.size ?: 0,
                shellStarts =
                    state
                        ?.shells
                        ?.values
                        ?.toList()
                        .orEmpty(),
            )
        }
    }

    /**
     * On open, reconnect and foreground (app-plan.md, When and Nothing new): the empty card for a session that never
     * ran; while it's busy nothing (the run's end refreshes); idle, a request unless the saved card is up to date with
     * the session's [updatedAt].
     */
    private fun refreshSummary(updatedAt: Long? = null) {
        val state = live
        when {
            state?.hasRun == false -> summary.showEmpty(day ?: today())
            state?.busySince != null -> {
                Log.i(TAG, "busy: no summary request; the run's end refreshes")
                summary.showWorking()
            }
            summary.isUpToDate(updatedAt) -> Log.i(TAG, "nothing new since the saved card (time.updated $updatedAt): no request")
            else -> {
                Log.i(TAG, "summary request (time.updated $updatedAt)")
                summary.refresh()
            }
        }
    }

    companion object {
        private const val TAG = "HexCode.Summary"

        /** The stream closes this long after the app goes to the background. */
        const val BACKGROUND_GRACE_MS = 5_000L

        /** After Stop, a subagent's note that starts the session again within this long gets one more interrupt. */
        const val STOP_RETRY_MS = 5_000L

        private const val STOP_TAG = "HexCode.Stop"
    }
}
