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
    /** Finding or creating today's session. Silent: the status area is empty and the top bar says Connecting…. */
    data object Opening : SessionSetup

    data class Ready(
        val sessionId: String,
    ) : SessionSetup

    /** "Couldn't open today's session" with Try again; [retrying] while Try again runs. */
    data class Failed(
        val retrying: Boolean = false,
    ) : SessionSetup

    /** The server refused the saved credentials: "Signed out of <name>" with Pair again. */
    data object SignedOut : SessionSetup
}

/** How Settings › Start a new session went. */
enum class NewSessionOutcome {
    /** The new session is open: "New session · The earlier one is kept on your computer." */
    Started,

    /** Creating it failed; today's session is still open: "Couldn't start a new session". */
    Failed,

    /** It was created but couldn't be opened: "Couldn't open today's session" with Try again. */
    SetupFailed,
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
    /** Today's goal; null when there is none (or before it's read). */
    val goal: Goal? = null,
    /** [you] is a goal set from this phone: "Goal: “…”" in place of "You: “…”". */
    val youGoal: Boolean = false,
    /**
     * The names of what went with [you]: its images, then its uploaded files (from its path lines), for "With
     * report.pdf" or "With 3 attachments" under it (app-plan.md, Attach files › You line).
     */
    val youWith: List<String> = emptyList(),
    /** With [newDayNotice]: yesterday's goal was still active, and keeps running there. */
    val newDayGoal: Boolean = false,
    /** The Ask message under the status card, while one shows. */
    val ask: AskMessage? = null,
    /** The closed Ask answers, newest first, for Details' Asked on the side. */
    val asked: List<AskedAnswer> = emptyList(),
    /**
     * What's saved on the phone has been read: the saved card shows if it's today's saved session's (app-plan.md,
     * Home › Cold start). The splash waits for this, never for the network.
     */
    val restored: Boolean = false,
    /** The open session's state has been read back once since the stream opened; until then the top bar says Connecting…. */
    val readBack: Boolean = false,
) {
    /** The goal line, or null for none: no goal while the agent works. */
    val goalLine: GoalLine? get() = GoalLine.of(goal, busySince != null)

    /** What runs besides the session at [now], for "N running · Details ›": subagents, and shells after 10 s. */
    fun running(now: Long): Int = subagentsRunning + shellStarts.count { now - it >= SHELL_SHOWN_MS }

    /**
     * The top bar's state: Connecting… while today's session is being opened and until its state is first read back
     * (Reconnecting… if the stream goes down before that), then [AgentStatus.of]. Null (an empty slot) with "Couldn't
     * open today's session" or Signed out.
     */
    val agent: AgentStatus?
        get() =
            when (setup) {
                SessionSetup.Opening -> AgentStatus.Connecting
                is SessionSetup.Ready ->
                    if (streamUp && !readBack) AgentStatus.Connecting else AgentStatus.of(streamUp, pendingInput != null, busySince)
                is SessionSetup.Failed, SessionSetup.SignedOut -> null
            }
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
     * `POST /api/session/{id}/prompt` with [text], [delivery] (`queue` or `steer`) and [images] in the same request,
     * and [metadata] when files went with it (app-plan.md, Attach files › The prompt); returns the inbox item's id.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        delivery: String,
        images: List<PromptImage> = emptyList(),
        metadata: JsonObject? = null,
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

    /** Today's subagents as the server lists them now (`GET /api/session?parentID=`). */
    suspend fun subagents(rootId: String): Set<String> = throw UnsupportedOperationException("No subagent list here")

    /** `POST /api/rpc/opencode.goal/get`: the session's goal; null when it has none. */
    suspend fun goal(
        sessionId: String,
        directory: String,
    ): Goal? = null

    /**
     * `POST /api/session/{id}/command` with `{"name": "goal", "text": …}` and [images] as `files`. Throws
     * [GoalChangedException] on a `500` (the goal changed elsewhere), or the server's error.
     */
    suspend fun goalCommand(
        sessionId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
    ): Unit = throw UnsupportedOperationException("No goal here")

    /**
     * Ask on the side: `POST /api/session/{id}/generate` with [prompt], at most [AskOnTheSide.TIMEOUT_MS]. Throws
     * [SessionGoneException] on a `404`.
     */
    suspend fun ask(
        sessionId: String,
        prompt: String,
    ): String = summarise(sessionId, prompt)
}

/** `/goal` failed with a `500`: the goal had already changed (paused, cleared or finished elsewhere). */
class GoalChangedException(
    message: String? = null,
) : Exception(message)

/** How a goal command went (app-plan.md, The goal sheet). */
enum class GoalOutcome {
    /** `204`: the state follows from the event, or from `get` 2 s later. */
    Done,

    /** A `500`: the goal had already changed; `get` was read again. */
    Stale,

    /** Anything else: "Couldn't change the goal". */
    Failed,
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
    /** Keeps today's Ask answers across launches. */
    askStore: AskStore? = null,
    /** A goal command whose event hasn't come this long after its `204` reads `get`. */
    private val goalSettleMs: Long = GOAL_SETTLE_MS,
    /** Runs at the start of every setup: renewing the sign-in and reading the server's version. Never fails it. */
    private val beforeSetup: suspend () -> Unit = {},
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

    /** Ask on the side: independent of the summary; a gone session runs setup again. */
    private val asking =
        AskOnTheSide(
            scope,
            generate = { prompt -> api.ask(sessionId ?: error("No session open"), prompt) },
            clock = clock,
            store = askStore,
            onSessionGone = ::open,
        )

    val state: StateFlow<TodayState> =
        combine(own, summary.state, asking.state) { state, card, ask ->
            state.copy(summary = card, ask = ask.message, asked = ask.history)
        }.stateIn(scope, SharingStarted.Eagerly, TodayState())

    private var live: LiveSessionState? = null

    /** What the phone sent, by inbox item, for the You line when it's delivered. */
    private val sent = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The names of the images the phone sent with each message, by inbox item. */
    private val sentImages = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** The message the You line shows, and whether its run has ended (the next card made while idle clears it). */
    private var you: String? = null
    private var youGoal = false
    private var youWith: List<String> = emptyList()
    private var youRunEnded = false

    /** The objective this phone is setting, until its kickoff shows up in the inbox. */
    @Volatile
    private var goalSetting: String? = null

    /** The objective of each kickoff this phone set, by inbox item, for "Goal: “…”" when it's delivered. */
    private val goalSent = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** This phone sent today's session a message since it opened. */
    @Volatile
    private var sentHere = false

    /** The local date today's session was opened for. */
    private var day: LocalDate? = null
    private var opening: Job? = null
    private var backgroundStop: Job? = null

    init {
        summary.onNewCard = { busy ->
            if (!busy && youRunEnded) {
                you = null
                youGoal = false
                youWith = emptyList()
                youRunEnded = false
                publish()
            }
        }
        stream?.onConnected = ::connected
        stream?.onDisconnected = { own.update { it.copy(streamUp = false) } }
        stream?.onEvent = ::received
    }

    /** Stops following today's session: the stream closes and nothing more is asked (the computer was forgotten). */
    fun close() {
        opening?.cancel()
        backgroundStop?.cancel()
        backgroundStop = null
        stream?.stop()
        sessionId = null
        live = null
        summary.clear()
        publish()
        own.update { TodayState() }
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
            SessionSetup.Failed(retrying = false), SessionSetup.SignedOut -> open()
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
     * Sends [text] with [images] (and the uploaded files' [metadata]) to today's session with [delivery]; returns the
     * inbox item's id, which [cancelQueued] takes back. Throws when the session isn't open or the server refuses it.
     */
    suspend fun send(
        text: String,
        delivery: String,
        images: List<PromptImage> = emptyList(),
        metadata: JsonObject? = null,
    ): String =
        api.send(sessionId ?: error("Today's session isn't open"), text, delivery, images, metadata).also {
            sent[it] = text
            if (images.isNotEmpty()) sentImages[it] = images.map { image -> image.name }
            // A new session's empty card no longer says nothing is running (app-plan.md, Empty session).
            sentHere = true
            summary.markStarted()
        }

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
     * that is running: in [LiveSessionApi.activeSessions] and in a fresh list of its subagents (else the ones known).
     * For [STOP_RETRY_MS] after, if a subagent's note starts the session again, or arrives while it's busy, it's
     * interrupted once more. Background shells are left running.
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
        val children =
            try {
                api.subagents(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                live?.children.orEmpty()
            }
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
        val retry =
            when {
                event is SessionEvent.InboxEnqueued && event.sessionId == watch.root && event.fromSubagent -> {
                    watch.noteSeen = true
                    live?.busySince != null
                }
                event is SessionEvent.ExecutionStarted && event.sessionId == watch.root -> watch.noteSeen
                else -> false
            }
        if (!retry) return
        stopWatch = null
        Log.i(STOP_TAG, "a subagent's note woke ${watch.root} again: interrupting it once more")
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

    /**
     * Runs `/goal` [command] on today's session (app-plan.md, The goal sheet), a new goal with [images] as `files`.
     * After a `204` the state comes from `rpc.opencode.goal.changed`, or from `get` when none comes within 2 s; a
     * Clear or a new goal then takes back the old goal's kickoff if it still waits in the inbox, or the agent would
     * still run a turn on it. A `500` means the goal changed elsewhere: `get` is read again.
     */
    suspend fun goal(
        command: GoalCommand,
        images: List<PromptImage> = emptyList(),
    ): GoalOutcome {
        val root = sessionId ?: return GoalOutcome.Failed
        val old = live?.goal
        val eventsBefore = live?.goalEvents ?: 0
        goalSetting = (command as? GoalCommand.Set)?.text
        try {
            api.goalCommand(root, command.text, images)
        } catch (error: CancellationException) {
            goalSetting = null
            throw error
        } catch (error: GoalChangedException) {
            goalSetting = null
            Log.i(GOAL_TAG, "/goal ${command.text.take(20)}: the goal had already changed (${error.message})")
            readGoal(root)
            return GoalOutcome.Stale
        } catch (error: Exception) {
            goalSetting = null
            Log.w(GOAL_TAG, "/goal ${command.text.take(20)} failed: ${error.message}")
            return GoalOutcome.Failed
        }
        if (old != null && (command is GoalCommand.Set || command == GoalCommand.Clear)) takeBackKickoff(root, old.id)
        scope.launch {
            delay(goalSettleMs)
            if (live?.rootId == root && live?.goalEvents == eventsBefore) {
                Log.i(GOAL_TAG, "no goal event ${goalSettleMs}ms after /goal: reading it")
                readGoal(root)
            }
        }
        return GoalOutcome.Done
    }

    /** Deletes the kickoff of goal [goalId] if it still waits in the inbox. */
    private suspend fun takeBackKickoff(
        root: String,
        goalId: String,
    ) {
        val items =
            live
                ?.goalItems
                .orEmpty()
                .filterValues { it == goalId }
                .keys
        for (item in items) {
            try {
                api.cancelQueued(root, item)
                Log.i(GOAL_TAG, "took back the old goal's kickoff $item")
                if (live?.rootId == root) changeLive { it.withoutInbox(item) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Delivered meanwhile, most likely.
                Log.w(GOAL_TAG, "couldn't take back the old kickoff $item: ${error.message}")
            }
        }
    }

    /** Reads today's goal with `get`; a failed read keeps the one shown. */
    private suspend fun readGoal(root: String) {
        val goal =
            try {
                api.goal(root, directory())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(GOAL_TAG, "couldn't read the goal: ${error.message}")
                return
            }
        if (live?.rootId == root) changeLive { it.withGoal(goal) }
    }

    /** Whether session [id]'s goal is still active (yesterday's, before moving on); false when it can't be read. */
    private suspend fun goalActive(
        id: String,
        directory: String,
    ): Boolean =
        try {
            api.goal(id, directory)?.status == GoalStatus.Active
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(GOAL_TAG, "couldn't read yesterday's goal: ${error.message}")
            false
        }

    /** Ask on the side with [question]: the message shows under the status card until it's closed. */
    fun ask(question: String) {
        if (sessionId != null) asking.ask(question)
    }

    /** Try again on a failed or empty Ask. */
    fun retryAsk() = asking.retry()

    /** ✕ on the Ask message. */
    fun closeAsk() = asking.close()

    /** A tap on the folded answer. */
    fun openAsk() = asking.expand()

    /** Fold on the open answer. */
    fun foldAsk() = asking.fold()

    /** A row of Details' Asked on the side, by its place in the history. */
    fun showAsked(index: Int) = asking.show(index)

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
        // What's saved on the phone first, before anything waits for the network (app-plan.md, Home › Cold start): the
        // card saved for today's saved session in this project shows while setup runs.
        summary.preload(date, directory, setup.savedSessionId(date))
        own.update { it.copy(restored = true) }
        try {
            beforeSetup()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "before setup: ${error.message}")
        }
        when (val result = setup.open(date, directory, model())) {
            DailySessionResult.Failed -> own.update { it.copy(setup = SessionSetup.Failed()) }
            DailySessionResult.SignedOut -> {
                // Nothing of the session it can no longer read stays on screen.
                summary.clear()
                own.update { it.copy(setup = SessionSetup.SignedOut) }
            }
            is DailySessionResult.Opened -> {
                // Yesterday's goal is left as it is; the notice says when it's still running there.
                val goalRunning = result.newDay && result.previousId?.let { goalActive(it, directory) } == true
                adopt(result.session, date, directory, result.newDay, goalRunning)
            }
        }
    }

    /**
     * Settings › Start a new session (app-plan.md, Settings and finish (M6)): creates the day's next numbered session
     * and follows it instead. The session before is never changed: no interrupt, no archive, no inbox cancel, and its
     * goal keeps running. Everything shown starts empty; the draft is home's and stays.
     */
    suspend fun startNew(): NewSessionOutcome {
        if (opening?.isActive == true) return NewSessionOutcome.Failed
        val date = today()
        val directory = directory()
        val result = setup.startNew(date, directory, model())
        return when (result) {
            NewSessionResult.CreateFailed -> NewSessionOutcome.Failed
            NewSessionResult.RulesFailed -> {
                // As a setup failure: Try again finds the new session, the highest number.
                stream?.stop()
                sessionId = null
                live = null
                summary.clear()
                publish()
                own.update { it.copy(setup = SessionSetup.Failed()) }
                NewSessionOutcome.SetupFailed
            }
            is NewSessionResult.Opened -> {
                stream?.stop()
                adopt(result.session, date, directory, newDay = false, goalRunning = false)
                NewSessionOutcome.Started
            }
        }
    }

    /** Follows [session] from now on: everything about the one before goes, then the stream opens and reads its state. */
    private suspend fun adopt(
        session: SessionListing,
        date: LocalDate,
        directory: String,
        newDay: Boolean,
        goalRunning: Boolean,
    ) {
        day = date
        sessionId = session.id
        live = LiveSessionState(session.id, hasRun = session.busySince != null)
        you = null
        youGoal = false
        youWith = emptyList()
        youRunEnded = false
        sentHere = false
        sent.clear()
        sentImages.clear()
        goalSent.clear()
        goalSetting = null
        stopWatch = null
        summary.open(session.id, date, directory)
        asking.open(session.id)
        own.update { it.copy(readBack = false) }
        publish(streamUp = true)
        // Without a stream nothing is read back: the top bar shows the state at once.
        own.update {
            it.copy(setup = SessionSetup.Ready(session.id), newDayNotice = newDay, newDayGoal = goalRunning, readBack = stream == null)
        }
        stream?.start()
        // With a stream, the first summary waits for its read-back, which says whether the agent is working
        // (an empty reply then isn't a failure); the saved card shows meanwhile. A session that never ran
        // shows its empty card now.
        if (stream == null || live?.hasRun == false) refreshSummary()
    }

    private suspend fun connected() {
        own.update { it.copy(streamUp = true) }
        val root = live?.rootId ?: return
        var updatedAt: Long? = null
        // Today's goal on every (re)connect, beside the rest; a failed read keeps the goal shown.
        scope.launch { readGoal(root) }
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
            own.update { it.copy(readBack = true) }
            if (live?.hasRun == false) showEmpty() else summary.refresh()
            return
        }
        own.update { it.copy(readBack = true) }
        refreshSummary(updatedAt)
    }

    private fun received(event: SessionEvent) {
        val current = live ?: return
        // The kickoff of a goal this phone set: it shows as "Goal: “…”" once it's delivered.
        if (event is SessionEvent.InboxEnqueued && event.sessionId == current.rootId && event.fromGoal) {
            val objective = goalSetting
            if (objective != null && (event.goalId != null || !event.synthetic)) {
                goalSent[event.id] = objective
                goalSetting = null
            }
        }
        if (event is SessionEvent.InboxRemoved && event.delivered && event.sessionId == current.rootId) {
            // "You: …" from now until the card made after this run ends; a later message replaces it. The goal's own
            // items aren't in the inbox kept here, so they never set it.
            val goal = goalSent.remove(event.id)
            val entry = current.inbox.firstOrNull { it.id == event.id }
            val text = goal ?: entry?.text?.ifEmpty { null } ?: sent[event.id]
            val images = entry?.files?.map { it.name ?: "" }?.takeIf { it.isNotEmpty() } ?: sentImages[event.id].orEmpty()
            sentImages.remove(event.id)
            if (text != null) {
                you = text
                youGoal = goal != null
                youWith = (if (goal != null) emptyList() else images) + PromptFiles.names(text)
                youRunEnded = false
            }
        }
        watchStop(event)
        val (next, effect) = current.apply(event, clock())
        live = next
        if (effect == LiveEffect.RunEnded && you != null) youRunEnded = true
        publish()
        when (effect) {
            // A run end waits for a refresh already running, so the card made mid-run is replaced.
            LiveEffect.RunEnded -> summary.refresh(runEnd = true)
            // A goal is one long run: each round's end refreshes the card, mid-run (Goal rounds refresh the summary).
            LiveEffect.GoalRound -> {
                Log.i(TAG, "goal round ${next.goal?.rounds}: summary request")
                summary.refresh()
            }
            null -> Unit
        }
    }

    private fun publish(streamUp: Boolean? = null) {
        val state = live
        // A new session that runs is no longer empty: its card says it's working on the message.
        if (state?.busySince != null) summary.markStarted()
        own.update {
            it.copy(
                streamUp = streamUp ?: it.streamUp,
                busySince = state?.busySince,
                pendingInput = state?.firstPending,
                waiting = state?.waiting ?: 0,
                queued = state?.queued.orEmpty(),
                todos = state?.todos.orEmpty(),
                you = you,
                youGoal = you != null && youGoal,
                youWith = if (you != null) youWith else emptyList(),
                goal = state?.goal,
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

    /** A new session's card; once this phone sent it a message, "Working on your message." (Empty session). */
    private fun showEmpty() {
        summary.showEmpty(day ?: today())
        if (sentHere) summary.markStarted()
    }

    /**
     * On open, reconnect and foreground (app-plan.md, When and Nothing new): the empty card for a session that never
     * ran; while it's busy nothing (the run's end refreshes); idle, a request unless the saved card is up to date with
     * the session's [updatedAt].
     */
    private fun refreshSummary(updatedAt: Long? = null) {
        val state = live
        when {
            state?.hasRun == false -> showEmpty()
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

        private const val GOAL_TAG = "HexCode.Goal"

        /** A goal command's state comes from its event; without one this long after the `204`, `get` is read. */
        const val GOAL_SETTLE_MS = 2_000L
    }
}
