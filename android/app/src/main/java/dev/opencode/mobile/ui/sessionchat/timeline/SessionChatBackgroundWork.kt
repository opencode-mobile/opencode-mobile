package dev.opencode.mobile.ui.sessionchat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.ToolStartTime
import dev.opencode.mobile.domain.message.ToolStatus
import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.shell.AgentShell
import dev.opencode.mobile.domain.time.RelativeTimeFormatting
import dev.opencode.mobile.ui.text.UiText
import dev.opencode.mobile.ui.text.resolve
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
    /** The agent as a small row tag; null for the default `general` agent or an unknown one. */
    val agentTag: String? = null,
    /** The latest child tool call ("Reading format.ts"); null when the server sends no child tool summary. */
    val currentStep: UiText? = null,
    /** Child tool calls so far; null when the server sends no child tool summary. */
    val toolCount: Int? = null,
    /** Done: the first line of the result. Failed or stopped: the reason. Null when unknown. */
    val result: String? = null,
) {
    val isActive: Boolean
        get() = status == SessionChatSubagentStatus.Running || status == SessionChatSubagentStatus.WaitingForInput

    val letter: String
        get() = SessionChatChildSessionElapsed.letter(agentName)

    /**
     * Live time while it works, the real run time once done; null when unknown or under a
     * second (a call that failed at once never ran), so a done row never reads `0:00`.
     */
    fun elapsed(nowMs: Long): String? {
        if (isActive) return SessionChatChildSessionElapsed.format(startedAtMs, nowMs)
        val start = ToolStartTime.normalizeEpochMs(startedAtMs) ?: return null
        val finished = ToolStartTime.normalizeEpochMs(finishedAtMs) ?: return null
        if (finished - start < 1_000) return null
        return SessionChatChildSessionElapsed.format(start, finished)
    }

    /** When a finished subagent ended: "now", "4m ago"; null while active or when unknown. */
    fun finishedAgo(nowMs: Long): UiText? {
        if (isActive) return null
        val finished = ToolStartTime.normalizeEpochMs(finishedAtMs) ?: return null
        return RelativeTimeFormatting.format(finished, nowMs)
    }
}

/** What a background work panel row says, in product words. */
data class SessionChatSubagentRowText(
    /** The task title; the agent name when the call named no task. */
    val title: String,
    val tag: String?,
    /** Running: the current step ("Waiting for input" while it waits). Done: the result or failure reason. */
    val detail: UiText?,
    /** Running: "12 tools". Done: when it finished, "4m ago". */
    val detailCaption: UiText?,
    /** Running: the live timer. Done: the real run time; null when unknown. */
    val time: String?,
)

/** A tab of the background work panel. The panel opens on [Running]. */
enum class SessionChatBackgroundWorkTab { Running, Done }

/** The background work panel split into its two tabs. */
data class SessionChatBackgroundWorkTabs(
    /** Waiting, then running subagents, each in spawn order. */
    val running: List<SessionChatSubagent>,
    /** Finished, failed and stopped subagents, newest first. */
    val done: List<SessionChatSubagent>,
)

/** What the background work pill says. Views resolve the text; agent names stay verbatim. */
data class SessionChatBackgroundWorkPill(
    /** "3 subagents", "1 subagent · 2 shells", or "general needs input · 2 running". */
    val labelText: UiText,
    /** A subagent waits for input: amber pill with a pulsing dot. */
    val needsInput: Boolean,
    val accessibilityText: UiText,
) {
    /** [labelText] in the current language. */
    val label: String
        @Composable get() = labelText.resolve()

    /** [accessibilityText] in the current language. */
    val accessibilityLabel: String
        @Composable get() = accessibilityText.resolve()
}

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
                val summary = childToolSummary(tool)
                SessionChatSubagent(
                    id = partId,
                    childSessionId = childId,
                    agentName = agentName(tool, session),
                    title = string(tool.input, "description") ?: session?.title?.takeIf { it.isNotEmpty() },
                    status = status,
                    startedAtMs = tool.timeStartEpochMs,
                    finishedAtMs = if (isActive) null else finishTime(tool, session, localFinishedAtMs[partId]),
                    agentTag = agentTag(knownAgentName(tool, session)),
                    currentStep = summary?.currentStep.takeIf { isActive },
                    toolCount = summary?.count,
                    result = if (isActive) null else result(tool, status),
                )
            }.sortedBy { rank(it.status) }
    }

    /**
     * Server end time first. A background call settles at spawn while its child keeps
     * working, so its own end time is skipped; the child session's last update stands in.
     * A finish the app saw live is the last resort. Times at or before the start are ignored,
     * so a settle stamped with the start time never reads as a `0:00` run.
     */
    fun finishTime(
        tool: MessagePart.Tool,
        session: Session?,
        local: Long?,
    ): Long? {
        val start = ToolStartTime.normalizeEpochMs(tool.timeStartEpochMs)
        return listOf(
            if (isBackground(tool)) null else tool.timeEndEpochMs,
            session?.updatedAtEpochMs,
            local,
        ).mapNotNull { ToolStartTime.normalizeEpochMs(it) }
            .firstOrNull { start == null || it > start }
    }

    /**
     * The panel tabs: Running holds waiting and running subagents in panel order, Done the
     * finished, failed and stopped ones, newest first (unknown finish times last, latest
     * spawned first among them).
     */
    fun tabs(subagents: List<SessionChatSubagent>): SessionChatBackgroundWorkTabs {
        val done =
            subagents
                .withIndex()
                .filter { !it.value.isActive }
                .sortedWith(
                    compareByDescending<IndexedValue<SessionChatSubagent>> {
                        ToolStartTime.normalizeEpochMs(it.value.finishedAtMs) ?: Long.MIN_VALUE
                    }.thenByDescending { it.index },
                ).map { it.value }
        return SessionChatBackgroundWorkTabs(running = subagents.filter { it.isActive }, done = done)
    }

    /**
     * Done rows the user has seen, for the Done tab's dot. The first rows of an open Session
     * Chat count as seen (null until any row shows), and so does everything while the Done
     * tab is on screen ([viewingDone]). A row that went back to work leaves the set, so its
     * next finish dots again.
     */
    fun seenDoneIds(
        previous: Set<String>?,
        subagents: List<SessionChatSubagent>,
        viewingDone: Boolean,
    ): Set<String>? {
        if (previous == null && subagents.isEmpty()) return null
        val doneIds = subagents.filterNot { it.isActive }.mapTo(mutableSetOf()) { it.id }
        if (previous == null || viewingDone) return doneIds
        return previous.intersect(doneIds)
    }

    /** Whether a subagent finished since the Done tab was last viewed. */
    fun hasUnseenDone(
        subagents: List<SessionChatSubagent>,
        seenDoneIds: Set<String>?,
    ): Boolean = seenDoneIds != null && subagents.any { !it.isActive && it.id !in seenDoneIds }

    /** The row copy for [subagent] at [nowMs]. Bits the server did not send stay null. */
    fun rowText(
        subagent: SessionChatSubagent,
        nowMs: Long,
    ): SessionChatSubagentRowText {
        val title = subagent.title?.takeIf { it.isNotBlank() } ?: subagent.agentName
        val time = subagent.elapsed(nowMs)
        if (!subagent.isActive) {
            val detail =
                subagent.result?.let(UiText::verbatim)
                    ?: UiText.resource(R.string.session_chat_sheet_background_work_stopped)
                        .takeIf { subagent.status == SessionChatSubagentStatus.Stopped }
            return SessionChatSubagentRowText(title, subagent.agentTag, detail, subagent.finishedAgo(nowMs), time)
        }
        val detail =
            if (subagent.status == SessionChatSubagentStatus.WaitingForInput) {
                UiText.resource(R.string.session_chat_sheet_background_work_waiting_for_input)
            } else {
                subagent.currentStep
            }
        return SessionChatSubagentRowText(title, subagent.agentTag, detail, subagent.toolCount?.let(::toolCountLabel), time)
    }

    /** "1 tool", "12 tools". */
    fun toolCountLabel(count: Int): UiText = UiText.plural(R.plurals.session_chat_sheet_background_work_tool_count, count)

    /** The agent tag rule: shown only for a named agent other than the default `general`. */
    fun agentTag(agentName: String?): String? = agentName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(DEFAULT_AGENT, ignoreCase = true) }

    /**
     * Done: the first line of the task output (v1 `<task_result>`, v2 `<subagent>` body), or
     * none for a background call, whose output only says it started. Failed or stopped: the
     * error without its `Subagent failed (task_id: …):` lead; null when there is none (the row
     * then says "Stopped" for a stopped call).
     */
    fun result(
        tool: MessagePart.Tool,
        status: SessionChatSubagentStatus,
    ): String? =
        when (status) {
            SessionChatSubagentStatus.Done -> if (isBackground(tool)) null else SessionChatSubagentResult.firstLine(tool.output)
            SessionChatSubagentStatus.Failed,
            SessionChatSubagentStatus.Stopped,
            ->
                SessionChatSubagentResult.reason(tool.error)
            SessionChatSubagentStatus.Running,
            SessionChatSubagentStatus.WaitingForInput,
            -> null
        }

    /**
     * The child tool calls a task part reports in `metadata.summary` (classic v1 up to 1.1.x
     * sends the child's tool parts there; later v1 and v2 send none): the count and the
     * latest call as a step title. Null when the part carries no summary.
     */
    fun childToolSummary(tool: MessagePart.Tool): SessionChatChildToolSummary? {
        val summary = tool.metadata?.get("summary") as? JsonArray ?: return null
        val calls =
            summary.mapIndexedNotNull { index, element ->
                val part = runCatching { element.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
                val state = part["state"] as? JsonObject
                val name = string(part, "tool") ?: return@mapIndexedNotNull null
                MessagePart.Tool(
                    toolName = name,
                    status =
                        when (string(state, "status")) {
                            "pending" -> ToolStatus.Pending
                            "running" -> ToolStatus.Running
                            "error" -> ToolStatus.Error
                            else -> ToolStatus.Completed
                        },
                    title = string(state, "title"),
                    input = state?.get("input") as? JsonObject,
                    id = string(part, "id") ?: "summary-$index",
                )
            }
        if (calls.isEmpty()) return null
        // The activity card's rule: the latest running call's running title, else the last call.
        val activity = SessionChatActivityCard.childActivity(listOf(Message(id = "summary", role = MessageRole.Assistant, text = "", parts = calls)))
        val step = activity.currentAction ?: activity.steps.lastOrNull()?.title
        val currentStep = step?.takeIf { it !is UiText.Verbatim || it.value.isNotBlank() }
        return SessionChatChildToolSummary(count = calls.size, currentStep = currentStep)
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
            return SessionChatBackgroundWorkPill(
                labelText = countLabel(running, runningShells),
                needsInput = false,
                accessibilityText =
                    UiText.resource(R.string.timeline_background_work_running_spoken, runningPhrase(running, runningShells)),
            )
        }
        val asking =
            if (waiting.size == 1) {
                UiText.resource(R.string.timeline_background_work_agent_needs_input, UiText.verbatim(firstWaiting.agentName))
            } else {
                UiText.plural(R.plurals.timeline_background_work_subagents_need_input, waiting.size)
            }
        val busy = running + runningShells
        val label = if (busy > 0) UiText.resource(R.string.timeline_background_work_needs_input_running, asking, busy) else asking
        val spoken =
            if (busy > 0) {
                UiText.resource(R.string.timeline_background_work_needs_input_running_spoken, asking, runningPhrase(running, runningShells))
            } else {
                asking
            }
        return SessionChatBackgroundWorkPill(labelText = label, needsInput = true, accessibilityText = spoken)
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

    private fun subagentCount(count: Int): UiText? =
        if (count == 0) null else UiText.plural(R.plurals.timeline_background_work_subagent_count, count)

    private fun shellCount(count: Int): UiText? =
        if (count == 0) null else UiText.plural(R.plurals.timeline_background_work_shell_count, count)

    /** "2 subagents", "1 shell", "1 subagent · 2 shells". */
    private fun countLabel(
        subagents: Int,
        shells: Int,
    ): UiText = joined(subagentCount(subagents), shellCount(shells), R.string.timeline_background_work_subagents_and_shells)

    /** "2 subagents", "1 shell", "1 subagent and 2 shells". */
    private fun runningPhrase(
        subagents: Int,
        shells: Int,
    ): UiText = joined(subagentCount(subagents), shellCount(shells), R.string.timeline_background_work_subagents_and_shells_spoken)

    /** Both counts through [bothRes], or whichever one is present. At least one is non-null. */
    private fun joined(
        subagents: UiText?,
        shells: UiText?,
        @StringRes bothRes: Int,
    ): UiText =
        when {
            subagents != null && shells != null -> UiText.resource(bothRes, subagents, shells)
            subagents != null -> subagents
            else -> shells ?: UiText.verbatim("")
        }

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
    ): String = knownAgentName(tool, session) ?: if (tool.toolName.equals("subagent", ignoreCase = true)) "General" else "Task"

    private fun knownAgentName(
        tool: MessagePart.Tool,
        session: Session?,
    ): String? =
        string(tool.input, "agent")
            ?: string(tool.input, "subagent_type")
            ?: string(tool.metadata, "agent")
            ?: session?.agentName
            ?: session?.let { InboxRequestAttribution.agentName(fromTitle = it.title) }

    private fun isBackground(tool: MessagePart.Tool): Boolean {
        val value = tool.metadata?.get("background") as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: (value.contentOrNull?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false)
    }

    private const val DEFAULT_AGENT = "general"

    private fun childSessionId(tool: MessagePart.Tool): String? = string(tool.metadata, "sessionID") ?: string(tool.metadata, "sessionId")

    private fun string(
        json: JsonObject?,
        key: String,
    ): String? = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
}

/** What a task part says about its child's tool calls: how many, and the latest as a step title. */
data class SessionChatChildToolSummary(
    val count: Int,
    val currentStep: UiText?,
)

/** Result and failure lines of a subagent's task part. */
object SessionChatSubagentResult {
    /**
     * First meaningful line of a task output: inside `<task_result>` or `<task_error>` when
     * present, skipping the `task_id:` header, wrapper tags such as `<subagent sessionID="…">`
     * and Markdown markers.
     */
    fun firstLine(output: String?): String? {
        val text = output?.takeIf { it.isNotBlank() } ?: return null
        val body =
            listOf("task_result", "task_error").firstNotNullOfOrNull { tag ->
                text.takeIf { "<$tag>" in it }?.substringAfter("<$tag>")?.substringBefore("</$tag>")
            } ?: text
        return body
            .lineSequence()
            .map { it.trim().trimStart('#', '*', '-', ' ').trimEnd('*') }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("task_id:") && !it.startsWith("<") }
    }

    /**
     * A failure as one line: "Subagent failed (task_id: ses_1): Rate limited" reads "Rate
     * limited", and "Subagent cancelled (sessionID: ses_1)" reads "Subagent cancelled".
     */
    fun reason(error: String?): String? {
        val line = firstLine(error) ?: return null
        val failed = FAILED_PREFIX.find(line)
        if (failed != null) return line.substring(failed.range.last + 1).trim().takeIf { it.isNotEmpty() } ?: line
        return line.replace(SESSION_REFERENCE, "").trim().takeIf { it.isNotEmpty() }
    }

    private val FAILED_PREFIX = Regex("""^Subagent failed \((?:task_id|sessionID): [^)]*\):""")
    private val SESSION_REFERENCE = Regex("""\s*\((?:task_id|sessionID): [^)]*\)""")
}
