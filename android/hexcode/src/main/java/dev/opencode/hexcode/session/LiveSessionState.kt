package dev.opencode.hexcode.session

/** A message waiting in today's inbox: its id and its delivery (`queue` or `steer`). */
data class InboxEntry(
    val id: String,
    val delivery: String,
    /** What the message says, for the Waiting to send sheet. */
    val text: String = "",
    /** Its files, by name and type; the server's copy of their data is never kept. */
    val files: List<InboxFile> = emptyList(),
) {
    /** How many of [files] are images, for "2 images" on a message the phone has no copy of. */
    val images: Int get() = files.count { it.mime == null || it.mime.startsWith("image/") }
}

/** A file of a waiting message: its name and type (`files[].name` and `mime`), without its data. */
data class InboxFile(
    val name: String?,
    val mime: String?,
)

/** A subagent of today's session: its task ([title]), its agent, and since when it has been running. */
data class ChildInfo(
    val title: String = "",
    val agent: String? = null,
    /** When its current run started; null when it isn't known or it isn't running. */
    val busySince: Long? = null,
)

/** An agent shell running now: its command and when it started. */
data class ShellRun(
    val id: String,
    val command: String,
    val startedAt: Long,
)

/** What Running now reads when Details opens (app-plan.md, Details › Reading Running now). */
data class RunningRead(
    /** Today's subagents, running or not, with their titles, agents and `time.busy`. */
    val children: Map<String, ChildInfo>,
    /** The subagents in `GET /api/session/active`. */
    val runningChildren: Set<String>,
    /** Agent shells of the session and its subagents. */
    val shells: List<ShellRun>,
)

/** The state read back on every (re)connect (app-plan.md, Today's session on screen). */
data class LiveSnapshot(
    /** When the current run started; null when today's session isn't running. */
    val busySince: Long?,
    /** The session has run at least once (`time.busy` is set). */
    val hasRun: Boolean,
    val children: List<String>,
    /** Pending approvals and questions of the session and its running subagents, oldest first. */
    val pending: List<PendingItem>,
    val inbox: List<InboxEntry>,
    /** The subagents that are running now. */
    val runningChildren: List<String> = emptyList(),
    /** The agent's to-do list from its newest `todowrite` call; empty when there is none. */
    val todos: List<TodoItem> = emptyList(),
    /** False when the to-do list couldn't be read: the one already shown stays. */
    val todosRead: Boolean = true,
    /** The session's `time.updated`: what the saved card's basis is checked against (app-plan.md, Nothing new). */
    val updatedAt: Long? = null,
    /** The subagents' titles, agents and `time.busy`. */
    val childInfo: Map<String, ChildInfo> = emptyMap(),
    /** The session's project: `global` for a folder that isn't a git repository (no Always there). */
    val projectId: String? = null,
)

/** What an event asks of the summary. */
enum class LiveEffect {
    /** A run of today's session ended: refresh the summary. */
    RunEnded,
}

/**
 * Today's session as the stream tells it: busy since when, its subagents and agent shells, what it waits for, and the
 * inbox. The server can't filter the stream by session, so events for other sessions change nothing here: an event
 * counts when its session is today's ([rootId]) or one of its subagents (a session whose parent is today's), and
 * `shell.exited` counts when its shell came from a counted `shell.created`.
 */
data class LiveSessionState(
    val rootId: String,
    val busySince: Long? = null,
    val hasRun: Boolean = false,
    val children: Set<String> = emptySet(),
    /** Subagents running now. */
    val runningChildren: Set<String> = emptySet(),
    /** Agent shells running now, with when each started. */
    val shells: Map<String, Long> = emptyMap(),
    /** Oldest first. */
    val pending: List<PendingItem> = emptyList(),
    /** In arrival order. */
    val inbox: List<InboxEntry> = emptyList(),
    /** The agent's to-do list; empty when it keeps none. */
    val todos: List<TodoItem> = emptyList(),
    /** What each subagent is doing, by id. */
    val childInfo: Map<String, ChildInfo> = emptyMap(),
    /** Each agent shell's command, by id. */
    val shellCommands: Map<String, String> = emptyMap(),
    /** The session's project id; null until the first read-back. */
    val projectId: String? = null,
    /** Counts today's session's finished tools, so Details can read Recent again after each. */
    val toolEvents: Int = 0,
) {
    /** Messages that send after this turn. */
    val waiting: Int get() = queued.size

    /** The messages that send after this turn, oldest first. */
    val queued: List<InboxEntry> get() = inbox.filter { it.delivery == "queue" }

    /**
     * What runs besides the session itself at [now], as Running now counts it: its subagents, and agent shells running
     * for [SHELL_SHOWN_MS] or longer.
     */
    fun running(now: Long): Int = runningChildren.size + shells.values.count { now - it >= SHELL_SHOWN_MS }

    /** The question or approval the hint card shows: the oldest. */
    val firstPending: PendingItem? get() = pending.firstOrNull()

    fun owns(sessionId: String): Boolean = sessionId == rootId || sessionId in children

    /** Takes the state read after a (re)connect. Agent shells seen on the stream are kept. */
    fun apply(snapshot: LiveSnapshot): LiveSessionState =
        copy(
            busySince = snapshot.busySince,
            hasRun = hasRun || snapshot.hasRun,
            children = snapshot.children.toSet(),
            runningChildren = snapshot.runningChildren.toSet(),
            pending = snapshot.pending,
            inbox = snapshot.inbox,
            todos = if (snapshot.todosRead) snapshot.todos else todos,
            childInfo = childInfo + snapshot.childInfo,
            projectId = snapshot.projectId ?: projectId,
        )

    /** Takes what Details read for Running now: the subagents and the agent shells, as they are now. */
    fun apply(running: RunningRead): LiveSessionState =
        copy(
            children = children + running.children.keys,
            childInfo = childInfo + running.children,
            runningChildren = running.runningChildren,
            shells = running.shells.associate { it.id to it.startedAt },
            shellCommands = running.shells.associate { it.id to it.command },
        )

    /** Without pending item [id], once it was answered from this phone: it leaves the queue before the event comes. */
    fun withoutPending(id: String): LiveSessionState = copy(pending = pending.filterNot { it.id == id })

    /**
     * After a reject on [sessionId]: the server rejects that session's other pending approvals with the same note, so
     * they go too.
     */
    fun withoutApprovals(sessionId: String): LiveSessionState =
        copy(pending = pending.filterNot { it.sessionId == sessionId && it.kind == PendingItem.Kind.Approval })

    /** Agent shells running now, with their commands. */
    val shellRuns: List<ShellRun> get() = shells.map { (id, at) -> ShellRun(id, shellCommands[id].orEmpty(), at) }

    /** Without inbox item [id], once a DELETE took it back: the row goes before the event says so. */
    fun withoutInbox(id: String): LiveSessionState = copy(inbox = inbox.filterNot { it.id == id })

    /** Applies one event at [now] (for a run start without a time): the next state, and what it asks of the summary. */
    fun apply(
        event: SessionEvent,
        now: Long,
    ): Pair<LiveSessionState, LiveEffect?> =
        when (event) {
            SessionEvent.Connected -> this to null
            is SessionEvent.ExecutionStarted ->
                when (event.sessionId) {
                    // One run includes the queued messages delivered during it: the first start is kept.
                    rootId -> copy(busySince = busySince ?: event.at ?: now, hasRun = true) to null
                    in children -> {
                        val info = childInfo[event.sessionId] ?: ChildInfo()
                        val since = if (event.sessionId in runningChildren) info.busySince ?: event.at ?: now else event.at ?: now
                        copy(
                            runningChildren = runningChildren + event.sessionId,
                            childInfo = childInfo + (event.sessionId to info.copy(busySince = since)),
                        ) to null
                    }
                    else -> this to null
                }
            // Pending ends with the run: Stop takes a pending approval away without `permission.replied`.
            is SessionEvent.ExecutionEnded ->
                when (event.sessionId) {
                    rootId -> copy(busySince = null, hasRun = true, pending = pendingWithout(event.sessionId)) to LiveEffect.RunEnded
                    in children ->
                        copy(runningChildren = runningChildren - event.sessionId, pending = pendingWithout(event.sessionId)) to null
                    else -> this to null
                }
            is SessionEvent.PermissionAsked -> addPending(event.item) to null
            is SessionEvent.FormCreated -> addPending(event.item) to null
            is SessionEvent.PermissionReplied -> removePending(event.sessionId, event.id) to null
            is SessionEvent.FormClosed -> removePending(event.sessionId, event.id) to null
            // A server note (a subagent's `<subagent …>`) isn't a message from the user: it never waits to send.
            is SessionEvent.InboxEnqueued ->
                if (event.sessionId != rootId || event.synthetic || inbox.any { it.id == event.id }) {
                    this to null
                } else {
                    copy(inbox = inbox + InboxEntry(event.id, event.delivery, event.text, event.files)) to null
                }
            is SessionEvent.InboxRemoved ->
                if (event.sessionId != rootId) this to null else copy(inbox = inbox.filterNot { it.id == event.id }) to null
            is SessionEvent.InboxDeliveryChanged ->
                if (event.sessionId != rootId) {
                    this to null
                } else {
                    copy(inbox = inbox.map { if (it.id == event.id) it.copy(delivery = event.delivery) else it }) to null
                }
            is SessionEvent.SessionCreated ->
                if (event.parentId == rootId) {
                    copy(children = children + event.id, childInfo = childInfo + (event.id to ChildInfo(event.title, event.agent))) to null
                } else {
                    this to null
                }
            is SessionEvent.ShellCreated ->
                if (owns(event.sessionId)) {
                    copy(shells = shells + (event.id to (event.at ?: now)), shellCommands = shellCommands + (event.id to event.command)) to
                        null
                } else {
                    this to null
                }
            is SessionEvent.ShellExited -> copy(shells = shells - event.id, shellCommands = shellCommands - event.id) to null
            // Only today's session's own list; a subagent keeps its own.
            is SessionEvent.TodosUpdated ->
                (if (event.sessionId == rootId) copy(todos = event.items, toolEvents = toolEvents + 1) else this) to null
            is SessionEvent.ToolFinished -> (if (event.sessionId == rootId) copy(toolEvents = toolEvents + 1) else this) to null
        }

    private fun pendingWithout(sessionId: String): List<PendingItem> = pending.filterNot { it.sessionId == sessionId }

    private fun addPending(item: PendingItem): LiveSessionState =
        if (!owns(item.sessionId) || pending.any { it.id == item.id }) this else copy(pending = pending + item)

    private fun removePending(
        sessionId: String,
        id: String,
    ): LiveSessionState = if (!owns(sessionId)) this else copy(pending = pending.filterNot { it.id == id })
}

/** A shell counts as running (Running now, the Details link) once it has run this long. */
const val SHELL_SHOWN_MS = 10_000L
