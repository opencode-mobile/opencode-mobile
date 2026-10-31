package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where one subagent of the open session is. */
enum class SessionChatSubagentStatus {
    Running,

    /** The subagent, or a subagent it started, has a pending permission or question. */
    WaitingForInput,
    Done,
}

/** One subagent the open session started, as the background work panel lists it. */
data class SessionChatSubagent(
    /** The subagent tool part id. */
    val id: String,
    /** The subagent's own session, once the server has named it. */
    val childSessionId: String?,
    val agentName: String,
    /** What the subagent was asked to do (the tool's `description`). */
    val title: String?,
    val status: SessionChatSubagentStatus,
    val startedAtMs: Long?,
    /** When the app saw it finish; null for subagents that finished before the chat opened. */
    val finishedAtMs: Long?,
) {
    val isActive: Boolean
        get() = status != SessionChatSubagentStatus.Done

    val letter: String
        get() = SessionChatChildSessionElapsed.letter(agentName)

    /** Live time while it works, the frozen run time once done, null when unknown. */
    fun elapsed(nowMs: Long): String? {
        if (isActive) return SessionChatChildSessionElapsed.format(startedAtMs, nowMs)
        val finished = finishedAtMs ?: return null
        return SessionChatChildSessionElapsed.format(startedAtMs, finished)
    }
}

/** What the background work pill says. */
data class SessionChatBackgroundWorkPill(
    /** "3 subagents", or "general needs input · 2 running". */
    val label: String,
    /** A subagent waits for input: amber pill with a pulsing dot. */
    val needsInput: Boolean,
    val accessibilityLabel: String,
)

/**
 * Subagents of the open session and the pill that sums them up. Pure: the
 * view model feeds it the loaded messages, live session activity and the
 * pending requests it already collects for the session tree.
 */
object SessionChatBackgroundWork {
    /** Subagent rows in panel order: waiting, then running, then done, each in spawn order. */
    fun subagents(
        messages: List<Message>,
        sessions: List<Session>,
        busySessionIds: Set<String>,
        requestSessionIds: List<String>,
        finishedAtMs: Map<String, Long> = emptyMap(),
    ): List<SessionChatSubagent> {
        val tools =
            messages
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
                .filter { it.id != null && SessionChatChildSessionLinker.isSubagentTool(it.toolName) }
        if (tools.isEmpty()) return emptyList()
        val sessionsById = sessions.associateBy { it.id }
        val childIds = tools.mapNotNullTo(mutableSetOf(), ::childSessionId)
        val waitingChildIds =
            requestSessionIds.mapNotNullTo(mutableSetOf()) { owningChild(it, childIds, sessionsById) }
        return tools
            .map { tool ->
                val partId = tool.id.orEmpty()
                val childId = childSessionId(tool)
                val session = childId?.let(sessionsById::get)
                val status =
                    when {
                        childId != null && childId in waitingChildIds -> SessionChatSubagentStatus.WaitingForInput
                        tool.status == ToolStatus.Running ||
                            tool.status == ToolStatus.Pending ||
                            (childId != null && childId in busySessionIds) -> SessionChatSubagentStatus.Running
                        else -> SessionChatSubagentStatus.Done
                    }
                SessionChatSubagent(
                    id = partId,
                    childSessionId = childId,
                    agentName = agentName(tool, session),
                    title = string(tool.input, "description") ?: session?.title?.takeIf { it.isNotEmpty() },
                    status = status,
                    startedAtMs = tool.timeStartEpochMs,
                    finishedAtMs = if (status == SessionChatSubagentStatus.Done) finishedAtMs[partId] else null,
                )
            }.sortedBy { rank(it.status) }
    }

    /** The pill for these rows; null when no subagent is working or waiting. */
    fun pill(subagents: List<SessionChatSubagent>): SessionChatBackgroundWorkPill? {
        val waiting = subagents.filter { it.status == SessionChatSubagentStatus.WaitingForInput }
        val running = subagents.count { it.status == SessionChatSubagentStatus.Running }
        val firstWaiting = waiting.firstOrNull()
        if (firstWaiting == null) {
            if (running == 0) return null
            val label = subagentCount(running)
            return SessionChatBackgroundWorkPill(label, needsInput = false, accessibilityLabel = "$label running")
        }
        val asking =
            if (waiting.size == 1) {
                "${firstWaiting.agentName} needs input"
            } else {
                "${subagentCount(waiting.size)} need input"
            }
        val label = if (running > 0) "$asking · $running running" else asking
        val spoken = if (running > 0) "$asking, ${subagentCount(running)} running" else asking
        return SessionChatBackgroundWorkPill(label, needsInput = true, accessibilityLabel = spoken)
    }

    /**
     * Remembers when subagents seen working finish, so their rows keep a frozen run time.
     * Entries for parts no longer listed are dropped.
     */
    fun finishTimes(
        previous: List<SessionChatSubagent>,
        current: List<SessionChatSubagent>,
        known: Map<String, Long>,
        nowMs: Long,
    ): Map<String, Long> {
        val wasActive = previous.filter { it.isActive }.mapTo(mutableSetOf()) { it.id }
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        val next = known.filterKeys { it in currentIds }.toMutableMap()
        for (row in current) {
            if (row.isActive) {
                next.remove(row.id)
            } else if (row.id !in next && row.id in wasActive) {
                next[row.id] = nowMs
            }
        }
        return next
    }

    private fun subagentCount(count: Int): String = if (count == 1) "1 subagent" else "$count subagents"

    private fun rank(status: SessionChatSubagentStatus): Int =
        when (status) {
            SessionChatSubagentStatus.WaitingForInput -> 0
            SessionChatSubagentStatus.Running -> 1
            SessionChatSubagentStatus.Done -> 2
        }

    /** The subagent child whose session tree holds [sessionId], walking up `parentId`. */
    private fun owningChild(
        sessionId: String,
        childIds: Set<String>,
        sessionsById: Map<String, Session>,
    ): String? {
        var current: String? = sessionId
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current)) {
            if (current in childIds) return current
            current = sessionsById[current]?.parentId
        }
        return null
    }

    /**
     * Tool input names the agent on both protocols; the session `agent` field
     * (v2) or the v1 title suffix covers the rest, like the request panels.
     */
    private fun agentName(
        tool: MessagePart.Tool,
        session: Session?,
    ): String =
        string(tool.input, "agent")
            ?: string(tool.input, "subagent_type")
            ?: string(tool.metadata, "agent")
            ?: session?.agentName
            ?: session?.let { InboxRequestAttribution.agentName(fromTitle = it.title) }
            ?: if (tool.toolName.equals("subagent", ignoreCase = true)) "General" else "Task"

    private fun childSessionId(tool: MessagePart.Tool): String? = string(tool.metadata, "sessionID") ?: string(tool.metadata, "sessionId")

    private fun string(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
}
