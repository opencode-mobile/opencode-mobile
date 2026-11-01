package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.ToolStartTime
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.shell.AgentShell
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Where one subagent of the open session is. */
enum class SessionChatSubagentStatus {
    Running,

    /** The subagent, or a subagent it started, has a pending permission or question. */
    WaitingForInput,
    Done,

    /** The subagent's call failed. */
    Failed,

    /** The user stopped the run (or declined the call) while the subagent worked. */
    Stopped,
}

/** One subagent the open session started, as the background work panel lists it. */
data class SessionChatSubagent(
    /** The id of the subagent's latest tool part (a resumed subagent keeps one row). */
    val id: String,
    /** The subagent's own session, once the server has named it. */
    val childSessionId: String?,
    val agentName: String,
    /** What the subagent was asked to do (the tool's `description`). */
    val title: String?,
    val status: SessionChatSubagentStatus,
    val startedAtMs: Long?,
    /** When it finished: server time when known, else when the app saw it finish live; null when unknown. */
    val finishedAtMs: Long?,
) {
    val isActive: Boolean
        get() = status == SessionChatSubagentStatus.Running || status == SessionChatSubagentStatus.WaitingForInput

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
    /** "3 subagents", "1 subagent · 2 shells", or "general needs input · 2 running". */
    val label: String,
    /** A subagent waits for input: amber pill with a pulsing dot. */
    val needsInput: Boolean,
    val accessibilityLabel: String,
)

/** The waiting request the background work pill opens when a subagent needs input. */
sealed interface SessionChatInboxTarget {
    data class Permission(
        val requestId: String,
    ) : SessionChatInboxTarget

    data class Question(
        val requestId: String,
    ) : SessionChatInboxTarget
}

/**
 * Subagents of the open session and the pill that sums them up. Pure: the
 * view model feeds it the loaded messages, live session activity and the
 * pending requests it already collects for the session tree.
 */
object SessionChatBackgroundWork {
    /**
     * Subagent rows in panel order: waiting, then running, then finished, each in spawn order.
     *
     * One row per child session: a resumed subagent (v1 `task_id`, or a resume the linker maps
     * through `input.sessionID`) adds a tool part for the same child, and only its latest part
     * counts, so the child's live state is never applied to an earlier, finished call.
     * [localFinishedAtMs] holds finishes the app saw live; server times win over them.
     */
    fun subagents(
        messages: List<Message>,
        sessions: List<Session>,
        busySessionIds: Set<String>,
        requestSessionIds: List<String>,
        localFinishedAtMs: Map<String, Long> = emptyMap(),
    ): List<SessionChatSubagent> {
        val tools =
            messages
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
                .filter { it.id != null && SessionChatChildSessionLinker.isSubagentTool(it.toolName) }
        if (tools.isEmpty()) return emptyList()
        val latestIndexByChild = mutableMapOf<String, Int>()
        tools.forEachIndexed { index, tool -> childSessionId(tool)?.let { latestIndexByChild[it] = index } }
        val latestTools =
            tools.filterIndexed { index, tool ->
                val childId = childSessionId(tool) ?: return@filterIndexed true
                latestIndexByChild[childId] == index
            }
        val sessionsById = sessions.associateBy { it.id }
        val childIds = latestIndexByChild.keys
        val waitingChildIds =
            requestSessionIds.mapNotNullTo(mutableSetOf()) { owningChild(it, childIds, sessionsById) }
        return latestTools
            .map { tool ->
                val partId = tool.id.orEmpty()
                val childId = childSessionId(tool)
                val session = childId?.let(sessionsById::get)
                val status =
                    when {
                        tool.status == ToolStatus.Error ->
                            if (tool.isInterrupted) SessionChatSubagentStatus.Stopped else SessionChatSubagentStatus.Failed
                        childId != null && childId in waitingChildIds -> SessionChatSubagentStatus.WaitingForInput
                        tool.status == ToolStatus.Running ||
                            tool.status == ToolStatus.Pending ||
                            (childId != null && childId in busySessionIds) -> SessionChatSubagentStatus.Running
                        else -> SessionChatSubagentStatus.Done
                    }
                val isActive =
                    status == SessionChatSubagentStatus.Running || status == SessionChatSubagentStatus.WaitingForInput
                SessionChatSubagent(
                    id = partId,
                    childSessionId = childId,
                    agentName = agentName(tool, session),
                    title = string(tool.input, "description") ?: session?.title?.takeIf { it.isNotEmpty() },
                    status = status,
                    startedAtMs = tool.timeStartEpochMs,
                    finishedAtMs = if (isActive) null else finishTime(tool, session, localFinishedAtMs[partId]),
                )
            }.sortedBy { rank(it.status) }
    }

    /**
     * Server end time first. A background call settles at spawn while its child keeps
     * working, so its own end time is skipped; the child session's last update stands in.
     * A finish the app saw live is the last resort. Times before the start are ignored.
     */
    fun finishTime(
        tool: MessagePart.Tool,
        session: Session?,
        local: Long?,
    ): Long? {
        val start = ToolStartTime.normalizeEpochMs(tool.timeStartEpochMs) ?: 0L
        return listOf(
            if (isBackground(tool)) null else tool.timeEndEpochMs,
            session?.updatedAtEpochMs,
            local,
        ).mapNotNull { ToolStartTime.normalizeEpochMs(it) }
            .firstOrNull { it >= start }
    }

    /**
     * Running shells in panel order: oldest first, shells with no start time last. Ended
     * shells leave the list.
     */
    fun runningShells(shells: List<AgentShell>): List<AgentShell> =
        shells
            .filter { it.isRunning }
            .sortedWith(compareBy(nullsLast()) { it.startedAtMs })

    /**
     * The pill for these rows; null when nothing is working or waiting. Running shells count
     * beside running subagents: "1 subagent · 2 shells", "general needs input · 3 running".
     */
    fun pill(
        subagents: List<SessionChatSubagent>,
        shells: List<AgentShell> = emptyList(),
    ): SessionChatBackgroundWorkPill? {
        val waiting = subagents.filter { it.status == SessionChatSubagentStatus.WaitingForInput }
        val running = subagents.count { it.status == SessionChatSubagentStatus.Running }
        val runningShells = shells.count { it.isRunning }
        val firstWaiting = waiting.firstOrNull()
        if (firstWaiting == null) {
            if (running == 0 && runningShells == 0) return null
            val label = listOfNotNull(subagentCount(running), shellCount(runningShells)).joinToString(" · ")
            return SessionChatBackgroundWorkPill(
                label,
                needsInput = false,
                accessibilityLabel = "${runningPhrase(running, runningShells)} running",
            )
        }
        val asking =
            if (waiting.size == 1) {
                "${firstWaiting.agentName} needs input"
            } else {
                "${subagentCount(waiting.size)} need input"
            }
        val busy = running + runningShells
        val label = if (busy > 0) "$asking · $busy running" else asking
        val spoken = if (busy > 0) "$asking, ${runningPhrase(running, runningShells)} running" else asking
        return SessionChatBackgroundWorkPill(label, needsInput = true, accessibilityLabel = spoken)
    }

    /**
     * The request the needs-input pill pages to: the first waiting subagent's permission,
     * else its question. A question waits behind any permission (the permission panel
     * shows first), so then the shown permission opens instead. Null when no subagent waits
     * or its request is gone, so the tap falls back to the subagents panel.
     */
    fun needsInputTarget(
        subagents: List<SessionChatSubagent>,
        sessions: List<Session>,
        permissions: List<PermissionRequest>,
        questions: List<QuestionRequest>,
        shownPermissionId: String?,
    ): SessionChatInboxTarget? {
        val childId =
            subagents.firstOrNull { it.status == SessionChatSubagentStatus.WaitingForInput }?.childSessionId
                ?: return null
        val childIds = subagents.mapNotNullTo(mutableSetOf()) { it.childSessionId }
        val sessionsById = sessions.associateBy { it.id }

        fun asks(sessionId: String) = owningChild(sessionId, childIds, sessionsById) == childId
        permissions.firstOrNull { asks(it.sessionId) }?.let { return SessionChatInboxTarget.Permission(it.id) }
        val question = questions.firstOrNull { asks(it.sessionId) } ?: return null
        if (permissions.isEmpty()) return SessionChatInboxTarget.Question(question.id)
        return SessionChatInboxTarget.Permission(shownPermissionId ?: permissions.first().id)
    }

    /**
     * Remembers when subagents finish while the app watches live, for rows without a server
     * finish time. [seenLive] is false when the previous rows predate the current live
     * connection (backgrounded, offline, reconnect refresh): the finish happened at some
     * unknown point while the app was away, so nothing is stamped.
     */
    fun finishTimes(
        previous: List<SessionChatSubagent>,
        current: List<SessionChatSubagent>,
        known: Map<String, Long>,
        nowMs: Long,
        seenLive: Boolean,
    ): Map<String, Long> {
        val wasActive = previous.filter { it.isActive }.mapTo(mutableSetOf()) { it.id }
        val currentIds = current.mapTo(mutableSetOf()) { it.id }
        val next = known.filterKeys { it in currentIds }.toMutableMap()
        for (row in current) {
            if (row.isActive) {
                next.remove(row.id)
            } else if (seenLive && row.finishedAtMs == null && row.id !in next && row.id in wasActive) {
                next[row.id] = nowMs
            }
        }
        return next
    }

    private fun subagentCount(count: Int): String? =
        when (count) {
            0 -> null
            1 -> "1 subagent"
            else -> "$count subagents"
        }

    private fun shellCount(count: Int): String? =
        when (count) {
            0 -> null
            1 -> "1 shell"
            else -> "$count shells"
        }

    /** "2 subagents", "1 shell", "1 subagent and 2 shells". */
    private fun runningPhrase(
        subagents: Int,
        shells: Int,
    ): String = listOfNotNull(subagentCount(subagents), shellCount(shells)).joinToString(" and ")

    private fun rank(status: SessionChatSubagentStatus): Int =
        when (status) {
            SessionChatSubagentStatus.WaitingForInput -> 0
            SessionChatSubagentStatus.Running -> 1
            SessionChatSubagentStatus.Done,
            SessionChatSubagentStatus.Failed,
            SessionChatSubagentStatus.Stopped,
            -> 2
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

    private fun isBackground(tool: MessagePart.Tool): Boolean {
        val value = tool.metadata?.get("background") as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.contentOrNull?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false)
    }

    private fun childSessionId(tool: MessagePart.Tool): String? = string(tool.metadata, "sessionID") ?: string(tool.metadata, "sessionId")

    private fun string(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
}
