package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.session.PermissionRequest
import dev.opencode.mobile.domain.session.QuestionInfo
import dev.opencode.mobile.domain.session.QuestionOption
import dev.opencode.mobile.domain.session.QuestionRequest
import dev.opencode.mobile.domain.session.Session
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object T3SessionMapper {
    private const val USER_INPUT_KIND = "user_input"
    private const val DYNAMIC_TOOL_KIND = "dynamic_tool_call"

    /** Turn items that describe the tool a `dynamic_tool_call` approval is waiting on. */
    private val TOOL_ITEM_TYPES = setOf("tool_call", "dynamic_tool", "command_execution", "file_change", "mcp_tool_call")
    private val SETTLED_ITEM_STATUSES = setOf("completed", "failed", "cancelled", "declined", "interrupted", "rolled_back")

    fun toSession(
        thread: T3Thread,
        directory: String? = null,
    ): Session =
        Session(
            id = thread.id,
            title = thread.title.ifBlank { "Untitled" },
            projectId = thread.projectId,
            directory = directory ?: thread.worktreePath,
            createdAtEpochMs = T3MessageMapper.epochMs(thread.createdAt),
            updatedAtEpochMs = T3MessageMapper.epochMs(thread.updatedAt),
            archivedAtEpochMs = T3MessageMapper.epochMs(thread.archivedAt),
            providerInstanceId = thread.providerInstanceId,
            runtimeMode = thread.runtimeMode,
            modelId = thread.modelSelection?.model,
            settledAtEpochMs = if (T3Activity.isSettled(thread)) T3MessageMapper.epochMs(thread.settledAt) else null,
        )

    fun approvalId(thread: T3Thread): String? {
        val id = thread.pendingRequestId?.takeIf { it.isNotBlank() } ?: return null
        if (thread.pendingRequestKind == USER_INPUT_KIND) return null
        return id
    }

    /** The pending `user_input` request id, or null when the thread is not waiting on the user. */
    fun questionId(thread: T3Thread): String? {
        val id = thread.pendingRequestId?.takeIf { it.isNotBlank() } ?: return null
        if (thread.pendingRequestKind != USER_INPUT_KIND) return null
        return id
    }

    /**
     * The approval prompt. [turnItems] (the thread projection) supplies what is being
     * approved; without them the card still shows the request kind.
     */
    fun toPermission(
        thread: T3Thread,
        turnItems: List<T3TurnItem> = emptyList(),
    ): PermissionRequest? {
        val id = approvalId(thread) ?: return null
        val kind = thread.pendingRequestKind?.takeIf { it.isNotBlank() } ?: return null
        val approval = turnItems.lastOrNull { it.type == "approval_request" && it.requestId == id }
        val tool = if (approval == null) pendingToolItem(turnItems) else null
        val requestKind = approval?.requestKind ?: kind
        val detail = approval?.prompt?.takeIf { it.isNotBlank() } ?: tool?.stringInput ?: tool?.fileName
        val toolName = tool?.toolName ?: approval?.appName
        val title = approval?.title ?: tool?.title
        val metadata =
            buildMap {
                put("kind", JsonPrimitive(requestKind))
                toolName?.let { put("tool", JsonPrimitive(it)) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", JsonPrimitive(it)) }
                approval?.appName?.let { put("appName", JsonPrimitive(it)) }
                if (detail != null) {
                    when (requestKind) {
                        "command" -> put("command", JsonPrimitive(detail))
                        "file-change", "file-read" -> put("filepath", JsonPrimitive(detail))
                        else -> put("prompt", JsonPrimitive(detail))
                    }
                }
                tool?.inputFields?.forEach { (key, value) -> putIfAbsent(key, JsonPrimitive(value)) }
            }
        return PermissionRequest(
            id = id,
            sessionId = thread.id,
            permission = permissionName(requestKind, toolName),
            patterns = listOfNotNull(detail),
            metadata = JsonObject(metadata),
            always = listOf("this session"),
            toolMessageId = null,
            toolCallId = null,
        )
    }

    /** The question card for a thread waiting on `user_input`; needs the thread's turn items. */
    fun toQuestionRequest(
        thread: T3Thread,
        turnItems: List<T3TurnItem>,
    ): QuestionRequest? {
        val id = questionId(thread) ?: return null
        val item =
            turnItems.lastOrNull { it.type == "user_input_request" && it.requestId == id }
                ?: turnItems.lastOrNull { it.type == "user_input_request" && it.status !in SETTLED_ITEM_STATUSES }
                ?: return null
        val questions = item.questions.map(::toQuestionInfo)
        if (questions.isEmpty()) return null
        return QuestionRequest(
            id = id,
            sessionId = thread.id,
            questions = questions,
            toolMessageId = null,
            toolCallId = null,
        )
    }

    fun toQuestionInfo(question: T3UserInputQuestion): QuestionInfo =
        QuestionInfo(
            question = question.question,
            header = question.header.ifBlank { question.question },
            options =
                question.options.map { option ->
                    QuestionOption(label = option.label, description = option.description, value = option.value ?: option.label)
                },
            multiple = question.multiSelect,
            custom = question.allowCustomAnswer,
            fieldKey = question.id,
        )

    fun toStatus(thread: T3Thread): SessionStatus {
        val type = if (T3Activity.isRunning(thread)) "running" else "idle"
        return SessionStatus(
            sessionId = thread.id,
            raw = mapOf("type" to type),
        )
    }

    private fun permissionName(
        requestKind: String,
        toolName: String?,
    ): String =
        when (requestKind) {
            DYNAMIC_TOOL_KIND -> toolName?.takeIf { it.isNotBlank() } ?: "tool"
            else -> requestKind.replace('-', '_')
        }

    private fun pendingToolItem(turnItems: List<T3TurnItem>): T3TurnItem? =
        turnItems.lastOrNull { it.type in TOOL_ITEM_TYPES && it.status?.lowercase() !in SETTLED_ITEM_STATUSES }
}
