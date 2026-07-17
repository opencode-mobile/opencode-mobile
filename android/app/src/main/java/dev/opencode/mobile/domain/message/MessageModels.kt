package dev.opencode.mobile.domain.message

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
    val contextInputTokens: Long? = null,
    val contextEstimate: MessageContextEstimate = MessageContextEstimate(),
)

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
)

sealed class MessagePart {
    data class Text(
        val text: String,
        val isStreaming: Boolean = false,
    ) : MessagePart()

    data class Tool(
        val toolName: String,
        val status: ToolStatus,
        val title: String? = null,
        val error: String? = null,
        val callId: String? = null,
        val input: JsonObject? = null,
        val output: String? = null,
        val metadata: JsonObject? = null,
    ) : MessagePart()

    data class File(
        val id: String,
        val filename: String,
        val mime: String,
        val kind: AttachmentKind,
        val dataUrl: String,
    ) : MessagePart()
}

data class PromptSendOptions(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val model: PromptModelSelection? = null,
    val variant: String? = null,
    val mode: String? = null,
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
