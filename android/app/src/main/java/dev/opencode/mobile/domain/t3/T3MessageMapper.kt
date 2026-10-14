package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.message.AttachmentKind
import dev.opencode.mobile.domain.message.FollowUpDelivery
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

object T3MessageMapper {
    private const val ACTIVITY_ID_PREFIX = "activity:"
    private const val REASONING_LIMIT = 32_000
    private const val DETAIL_LIMIT = 4_000

    fun toMessages(snapshot: T3ThreadSnapshot): List<Message> =
        applyingQueue(
            transcriptMessages(snapshot.messages, snapshot.turnItems),
            snapshot.messages,
            snapshot.runs,
        )

    fun transcriptMessages(
        messages: List<T3ConversationMessage>,
        turnItems: List<T3TurnItem>,
    ): List<Message> {
        if (turnItems.isEmpty()) return messages.map(::toMessage)
        val byId = HashMap<String, T3ConversationMessage>(messages.size)
        for (message in messages) {
            if (message.id !in byId) byId[message.id] = message
        }
        val emitted = mutableSetOf<String>()
        val result = mutableListOf<Message>()
        val buffer = mutableListOf<MessagePart>()
        var bufferStartedAt: String? = null

        // Consecutive activity shares one assistant message so the timeline can group the tools.
        fun flush() {
            if (buffer.isEmpty()) return
            val firstId = partId(buffer.first())
            if (firstId.isNullOrBlank()) {
                buffer.clear()
                bufferStartedAt = null
                return
            }
            result +=
                Message(
                    id = "$ACTIVITY_ID_PREFIX$firstId",
                    role = MessageRole.Assistant,
                    text = "",
                    parts = buffer.toList(),
                    sentAtEpochMs = epochMs(bufferStartedAt),
                )
            buffer.clear()
            bufferStartedAt = null
        }

        for (item in turnItems) {
            if (item.type == "user_message" || item.type == "assistant_message") {
                flush()
                val messageId = item.messageId?.trim().orEmpty()
                if (messageId.isEmpty() || !emitted.add(messageId)) continue
                val source = byId[messageId]
                result +=
                    if (source != null) {
                        toMessage(source)
                    } else {
                        toMessage(messageFromTurnItem(item, messageId))
                    }
                continue
            }
            val part = activityPart(item) ?: continue
            if (buffer.isEmpty()) bufferStartedAt = item.startedAt
            buffer += part
        }
        flush()
        for (message in messages) {
            if (emitted.add(message.id)) result += toMessage(message)
        }
        return result
    }

    fun upsertConversationMessage(
        messages: List<T3ConversationMessage>,
        message: T3ConversationMessage,
    ): List<T3ConversationMessage> {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index < 0) return messages + message
        if (messages[index] == message) return messages
        val next = messages.toMutableList()
        next[index] = message
        return next
    }

    fun upsertTurnItem(
        items: List<T3TurnItem>,
        item: T3TurnItem,
    ): List<T3TurnItem> {
        val existing = items.indexOfFirst { it.id == item.id }
        if (existing >= 0) {
            if (items[existing] == item) return items
            val next = items.toMutableList()
            next[existing] = item
            return next
        }
        val index = items.indexOfFirst { other -> turnItemPrecedes(item, other) }
        val next = items.toMutableList()
        next.add(if (index < 0) next.size else index, item)
        return next
    }

    /**
     * A user message whose run is still `queued` is waiting for the agent loop.
     * The timeline hides it and the pending-prompt strip offers Steer.
     * Automatic completion runs stay out of that strip.
     */
    fun applyingQueue(
        messages: List<Message>,
        conversation: List<T3ConversationMessage>,
        runs: List<T3Run>,
    ): List<Message> {
        val waiting = T3Queue.userQueuedRuns(runs, conversation).mapTo(HashSet()) { it.userMessageId }
        if (waiting.isEmpty() && messages.none { it.isWaitingForPickup }) return messages
        return messages.map { message ->
            val queued = message.role == MessageRole.User && message.id in waiting
            when {
                queued && message.isWaitingForPickup && message.followUpDelivery == FollowUpDelivery.Queue -> message
                queued -> message.copy(isWaitingForPickup = true, followUpDelivery = FollowUpDelivery.Queue)
                message.isWaitingForPickup || message.followUpDelivery != null ->
                    message.copy(isWaitingForPickup = false, followUpDelivery = null)
                else -> message
            }
        }
    }

    fun toMessage(message: T3ConversationMessage): Message {
        val role = roleOf(message.role)
        val text = withoutContextImages(message.text)
        return Message(
            id = message.id,
            role = role,
            text = text,
            parts =
                buildList {
                    add(
                        MessagePart.Text(
                            text = text,
                            isStreaming = message.streaming,
                            id = message.id,
                        ),
                    )
                    message.attachments.forEach { attachment ->
                        add(filePart(attachment))
                    }
                },
            sentAtEpochMs = epochMs(message.createdAt),
        )
    }

    /**
     * The timeline decodes a data URL only when its mime matches the file part and the
     * string stays under 512KB. Thumbnails are JPEG, so a filled image part uses that mime.
     */
    fun applyingImagePreviews(
        messages: List<Message>,
        previews: Map<String, String>,
    ): List<Message> {
        if (previews.isEmpty()) return messages
        return messages.map { message ->
            var changed = false
            val parts =
                message.parts.map { part ->
                    if (part is MessagePart.File && part.kind == AttachmentKind.Image) {
                        val dataUrl = previews[part.id]
                        if (dataUrl != null && part.dataUrl != dataUrl) {
                            changed = true
                            part.copy(mime = T3Assets.PREVIEW_MIME, dataUrl = dataUrl)
                        } else {
                            part
                        }
                    } else {
                        part
                    }
                }
            if (changed) message.copy(parts = parts) else message
        }
    }

    fun imageFilesNeedingPreview(messages: List<Message>): List<MessagePart.File> =
        messages.flatMap { message ->
            message.parts.mapNotNull { part ->
                (part as? MessagePart.File)?.takeIf { file ->
                    file.kind == AttachmentKind.Image &&
                        file.mime.startsWith("image/", ignoreCase = true) &&
                        file.dataUrl.isEmpty()
                }
            }
        }

    private val contextImage = Regex("""!\[[^\]]*]\(t3-context:[^)]*\)\s*""")

    fun withoutContextImages(text: String): String = contextImage.replace(text, "").trim()

    private fun filePart(attachment: T3Attachment): MessagePart.File {
        val mime = attachment.mimeType.lowercase()
        val kind =
            when {
                attachment.type == "image" || mime.startsWith("image/") -> AttachmentKind.Image
                mime == "application/pdf" -> AttachmentKind.Pdf
                else -> AttachmentKind.Text
            }
        return MessagePart.File(
            id = attachment.id,
            filename = attachment.name,
            mime = attachment.mimeType,
            kind = kind,
            dataUrl = "",
        )
    }

    private fun messageFromTurnItem(
        item: T3TurnItem,
        messageId: String,
    ): T3ConversationMessage =
        T3ConversationMessage(
            id = messageId,
            role = if (item.type == "user_message") "user" else "assistant",
            text = item.text.orEmpty(),
            streaming = item.streaming,
            createdAt = item.startedAt,
            attachments = item.attachments,
        )

    private fun turnItemPrecedes(
        item: T3TurnItem,
        other: T3TurnItem,
    ): Boolean {
        val ordinal = item.ordinal
        val otherOrdinal = other.ordinal
        return when {
            ordinal != null && otherOrdinal != null && ordinal != otherOrdinal -> ordinal < otherOrdinal
            ordinal != null && otherOrdinal == null -> true
            ordinal == null && otherOrdinal != null -> false
            else -> item.id < other.id
        }
    }

    private fun activityPart(item: T3TurnItem): MessagePart? {
        if (item.id.isBlank()) return null
        return when (item.type) {
            "reasoning" -> reasoningPart(item)
            "dynamic_tool" -> dynamicTool(item)
            "command_execution" -> commandTool(item)
            "file_search" ->
                namedSearch(
                    item,
                    toolName = "grep",
                    key = "pattern",
                    value = item.pattern?.takeIf { it.isNotBlank() } ?: nonBlank(item.title),
                )
            "web_search" ->
                namedSearch(
                    item,
                    toolName = "websearch",
                    key = "query",
                    value = item.patterns.firstOrNull { it.isNotBlank() } ?: nonBlank(item.title),
                )
            "file_change" -> fileChange(item)
            "subagent" -> subagentTool(item)
            else -> null
        }
    }

    private fun reasoningPart(item: T3TurnItem): MessagePart.Reasoning {
        val text = item.text.orEmpty().take(REASONING_LIMIT)
        // Streaming thoughts stay in the working footer. A non-empty trace has to be settled to draw.
        val streaming = text.isBlank() && item.streaming
        return MessagePart.Reasoning(
            text = text,
            isStreaming = streaming,
            id = item.id,
            timeStartEpochMs = epochMs(item.startedAt),
            timeEndEpochMs = epochMs(item.completedAt),
        )
    }

    private fun dynamicTool(item: T3TurnItem): MessagePart.Tool {
        val name = dynamicToolName(item.toolName)
        val fields = item.inputFields
        val input =
            when (name) {
                "read" -> jsonField("filePath", firstRaw(fields, "path", "filePath", "file_path", "target_file", "file"))
                "grep" -> jsonField("pattern", firstRaw(fields, "pattern", "query") ?: nonBlank(item.title))
                "glob" -> jsonField("pattern", firstRaw(fields, "glob", "pattern", "path"))
                "bash" -> jsonField("command", firstRaw(fields, "command", "cmd") ?: item.stringInput)
                "edit" -> jsonField("filePath", firstRaw(fields, "path", "filePath"))
                else -> null
            }
        return toolPart(
            item = item,
            toolName = name,
            title = nonBlank(item.title),
            input = input,
            output = cappedOutput(item.output),
            metadata = null,
        )
    }

    private fun dynamicToolName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        val stripped = trimmed.lowercase().filter { it != '_' && it != '-' && it != ' ' }
        return when (stripped) {
            "read", "readfile" -> "read"
            "grep", "rg", "find", "ls" -> "grep"
            "glob" -> "glob"
            "bash", "shell", "terminal" -> "bash"
            "edit", "write", "applypatch" -> "edit"
            else -> {
                val lower = trimmed.lowercase()
                if (lower.isEmpty() || lower == "tool") "activity" else lower
            }
        }
    }

    private fun commandTool(item: T3TurnItem): MessagePart.Tool {
        val command = item.stringInput.orEmpty()
        val title = nonBlank(item.title) ?: nonBlank(command.substringBefore('\n'))
        return toolPart(
            item = item,
            toolName = "bash",
            title = title,
            input = buildJsonObject { put("command", command) },
            output = cappedOutput(item.output),
            metadata = item.exitCode?.let { code -> buildJsonObject { put("exit", code) } },
        )
    }

    private fun fileChange(item: T3TurnItem): MessagePart.Tool {
        val fileName = item.fileName?.takeIf { it.isNotBlank() }
        return toolPart(
            item = item,
            toolName = "edit",
            title = fileName,
            input = jsonField("filePath", fileName),
            output = cappedOutput(item.output),
            metadata = null,
        )
    }

    private fun namedSearch(
        item: T3TurnItem,
        toolName: String,
        key: String,
        value: String?,
    ): MessagePart.Tool =
        toolPart(
            item = item,
            toolName = toolName,
            title = nonBlank(item.title),
            input = jsonField(key, value),
            output = cappedOutput(item.output),
            metadata = null,
        )

    private fun subagentTool(item: T3TurnItem): MessagePart.Tool {
        val agent = nonBlank(item.title) ?: "Subagent"
        val description =
            listOf(item.prompt, item.progress, item.result)
                .firstNotNullOfOrNull(::nonBlank)
                ?.take(DETAIL_LIMIT)
        val sessionId = item.childThreadId?.trim()?.takeIf { it.isNotEmpty() }
        return toolPart(
            item = item,
            toolName = "subagent",
            title = nonBlank(item.title),
            input =
                buildJsonObject {
                    put("agent", agent)
                    if (description != null) put("description", description)
                },
            output = cappedOutput(item.output),
            metadata = sessionId?.let { id -> buildJsonObject { put("sessionId", id) } },
        )
    }

    private fun toolPart(
        item: T3TurnItem,
        toolName: String,
        title: String?,
        input: JsonObject?,
        output: String?,
        metadata: JsonObject?,
    ): MessagePart.Tool {
        val (status, interrupted) = toolState(item.status)
        return MessagePart.Tool(
            toolName = toolName,
            status = status,
            title = title,
            input = input,
            output = output,
            metadata = metadata,
            id = item.id,
            callId = item.id,
            timeStartEpochMs = epochMs(item.startedAt),
            isInterrupted = interrupted,
        )
    }

    private fun toolState(status: String?): Pair<ToolStatus, Boolean> =
        when (status?.trim()?.lowercase()) {
            "running", "waiting" -> ToolStatus.Running to false
            "completed" -> ToolStatus.Completed to false
            "failed" -> ToolStatus.Error to false
            "cancelled", "interrupted" -> ToolStatus.Error to true
            else -> ToolStatus.Pending to false
        }

    private fun jsonField(
        key: String,
        value: String?,
    ): JsonObject? {
        if (value == null) return null
        return buildJsonObject { put(key, value) }
    }

    private fun firstRaw(
        fields: Map<String, String>,
        vararg keys: String,
    ): String? {
        for (key in keys) {
            val value = fields[key] ?: continue
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun cappedOutput(value: String?): String? = value?.take(DETAIL_LIMIT)?.takeIf { it.isNotEmpty() }

    private fun partId(part: MessagePart): String? =
        when (part) {
            is MessagePart.Text -> part.id
            is MessagePart.Tool -> part.id
            is MessagePart.File -> part.id
            is MessagePart.Compaction -> part.id
            is MessagePart.Reasoning -> part.id
        }

    fun roleOf(raw: String): MessageRole =
        when (raw.lowercase()) {
            "user" -> MessageRole.User
            "assistant", "system" -> MessageRole.Assistant
            else -> MessageRole.Unknown
        }

    fun epochMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}

/** The run Steer interrupts, and the queued run it promotes. */
data class T3SteerTarget(
    val queuedRunId: String,
    val activeRunId: String,
)

/**
 * Same queue the desktop composer shows. A `queued` run whose user message is
 * not an automatic completion is waiting to be picked up. Steer needs a live
 * turn (`preparing`, `starting`, `running`, or `waiting`) to interrupt.
 */
object T3Queue {
    private val activeStatuses = setOf("preparing", "starting", "running", "waiting")

    fun userQueuedRuns(
        runs: List<T3Run>,
        messages: List<T3ConversationMessage>,
    ): List<T3Run> {
        val automatic = messages.filter { it.automatic }.mapTo(HashSet()) { it.id }
        return runs
            .filter { run ->
                run.status.equals("queued", ignoreCase = true) && run.userMessageId !in automatic
            }.sortedWith(compareBy<T3Run> { it.queuePosition ?: it.ordinal }.thenBy { it.ordinal })
    }

    fun activeRunId(runs: List<T3Run>): String? = runs.lastOrNull { it.status.lowercase() in activeStatuses }?.id

    fun steerTarget(
        runs: List<T3Run>,
        messages: List<T3ConversationMessage>,
        messageId: String,
    ): T3SteerTarget? {
        val queued = userQueuedRuns(runs, messages).firstOrNull { it.userMessageId == messageId } ?: return null
        val active = activeRunId(runs) ?: return null
        return T3SteerTarget(queuedRunId = queued.id, activeRunId = active)
    }
}
