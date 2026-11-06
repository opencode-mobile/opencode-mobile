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
        )

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
                    in children -> copy(runningChildren = runningChildren + event.sessionId) to null
                    else -> this to null
                }
            is SessionEvent.ExecutionEnded ->
                when (event.sessionId) {
                    rootId -> copy(busySince = null, hasRun = true) to LiveEffect.RunEnded
                    in children -> copy(runningChildren = runningChildren - event.sessionId) to null
                    else -> this to null
                }
            is SessionEvent.PermissionAsked -> addPending(event.item) to null
            is SessionEvent.FormCreated -> addPending(event.item) to null
            is SessionEvent.PermissionReplied -> removePending(event.sessionId, event.id) to null
            is SessionEvent.FormClosed -> removePending(event.sessionId, event.id) to null
            is SessionEvent.InboxEnqueued ->
                if (event.sessionId != rootId || inbox.any { it.id == event.id }) {
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
            is SessionEvent.SessionCreated -> (if (event.parentId == rootId) copy(children = children + event.id) else this) to null
            is SessionEvent.ShellCreated ->
                (if (owns(event.sessionId)) copy(shells = shells + (event.id to (event.at ?: now))) else this) to null
            is SessionEvent.ShellExited -> copy(shells = shells - event.id) to null
            // Only today's session's own list; a subagent keeps its own.
            is SessionEvent.TodosUpdated -> (if (event.sessionId == rootId) copy(todos = event.items) else this) to null
        }

    private fun addPending(item: PendingItem): LiveSessionState =
        if (!owns(item.sessionId) || pending.any { it.id == item.id }) this else copy(pending = pending + item)

    private fun removePending(
        sessionId: String,
        id: String,
    ): LiveSessionState = if (!owns(sessionId)) this else copy(pending = pending.filterNot { it.id == id })
}

/** A shell counts as running (Running now, the Details link) once it has run this long. */
const val SHELL_SHOWN_MS = 10_000L
