package dev.opencode.mobile.domain.message

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

sealed class MessagePart {
    data class Text(
        val text: String,
        val isStreaming: Boolean = false,
    ) : MessagePart()

    data class Tool(
        val toolName: String,
        val statusText: String,
    ) : MessagePart()
}

data class PromptSendOptions(
    val text: String,
    val model: PromptModelSelection? = null,
    val variant: String? = null,
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
