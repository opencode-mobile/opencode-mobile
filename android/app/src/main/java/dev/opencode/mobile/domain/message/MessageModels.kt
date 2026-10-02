package dev.opencode.mobile.domain.message

import dev.opencode.mobile.domain.agent.AgentMention
import kotlinx.serialization.json.JsonObject

data class Message(
    val id: String,
    val role: MessageRole,
    val text: String,
    val parts: List<MessagePart> = emptyList(),
    val cost: Double? = null,
    val tokens: MessageTokenUsage? = null,
    val model: String? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val mode: String? = null,
    val sentAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val contextInputTokens: Long? = null,
    val contextEstimate: MessageContextEstimate = MessageContextEstimate(),
    val followUpDelivery: FollowUpDelivery? = null,
    val isWaitingForPickup: Boolean = false,
    /** Why an assistant reply ended early; null for a complete (or still running) reply. */
    val stopReason: MessageStopReason? = null,
)

/** An assistant reply that ended before the model finished its answer. */
enum class MessageStopReason {
    /** The model hit its output limit (`finish: "length"`). */
    Length,

    /** The user stopped the run (`error.type: "aborted"` / `MessageAbortedError`). */
    Interrupted,
    ;

    companion object {
        /**
         * [finish] is the step finish reason (`"aborted"` is how the v2 live projection
         * marks an aborted step); [errorKind] is the v2 error `type` or v1 error `name`.
         */
        fun from(
            finish: String?,
            errorKind: String?,
        ): MessageStopReason? =
            when {
                finish.equals("length", ignoreCase = true) -> Length
                finish.equals("aborted", ignoreCase = true) -> Interrupted
                ToolInterruption.isUserAbortError(type = errorKind, name = errorKind) -> Interrupted
                else -> null
            }
    }
}

data class MessageTokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
) {
    val total: Long = input + output + reasoning + cacheRead + cacheWrite
}

data class MessageContextEstimate(
    val systemCharacters: Int = 0,
    val userCharacters: Int = 0,
    val assistantCharacters: Int = 0,
    val toolCharacters: Int = 0,
)

enum class ToolStatus {
    Pending,
    Running,
    Completed,
    Error,
}

enum class AttachmentKind {
    Image,
    Pdf,
    Text,
}

data class Attachment(
    val id: String,
    val filename: String,
    val mime: String,
    val kind: AttachmentKind,
    val dataUrl: String,
    val byteCount: Long,
    val previewBytes: ByteArray? = null,
    val localPath: String? = null,
) {
    val usesFileBackedBody: Boolean
        get() = localPath != null
}

sealed class MessagePart {
    data class Text(
        val text: String,
        val isStreaming: Boolean = false,
        val id: String? = null,
        /** Server-injected context (e.g. decoded text-file attachment body). Hidden in UI. */
        val isSynthetic: Boolean = false,
        /** Server-marked non-display text. Hidden in UI. */
        val isIgnored: Boolean = false,
    ) : MessagePart() {
        val isDisplayable: Boolean
            get() = !isSynthetic && !isIgnored
    }

    data class Tool(
        val toolName: String,
        val status: ToolStatus,
        val title: String? = null,
        val error: String? = null,
        val callId: String? = null,
        val input: JsonObject? = null,
        val output: String? = null,
        val metadata: JsonObject? = null,
        val id: String? = null,
        val timeStartEpochMs: Long? = null,
        /** Failed because the user stopped the run or declined the call, not a real failure. */
        val isInterrupted: Boolean = false,
    ) : MessagePart()

    data class File(
        val id: String,
        val filename: String,
        val mime: String,
        val kind: AttachmentKind,
        val dataUrl: String,
    ) : MessagePart()

    /**
     * Server compaction marker. Drives live Compacting… detection; on an
     * assistant (v2) message it renders as a compaction row whose [summary]
     * stays behind a disclosure, never as reply text.
     */
    data class Compaction(
        val id: String? = null,
        val auto: Boolean = false,
        val completed: Boolean = false,
        val summary: String? = null,
    ) : MessagePart()

    data class Reasoning(
        val text: String,
        val isStreaming: Boolean = false,
        val id: String? = null,
        val timeStartEpochMs: Long? = null,
        val timeEndEpochMs: Long? = null,
    ) : MessagePart()
}

data class PromptSendOptions(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
    val agentMentions: List<AgentMention> = emptyList(),
    val delivery: FollowUpDelivery = FollowUpDelivery.Steer,
)

data class PromptModelSelection(
    val providerId: String,
    val modelId: String,
)

enum class MessageRole {
    User,
    Assistant,
    Command,
    Tool,
    Unknown,
}

sealed class MessageError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : MessageError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : MessageError()

    data class Decoding(
        override val cause: Throwable,
    ) : MessageError()
}
