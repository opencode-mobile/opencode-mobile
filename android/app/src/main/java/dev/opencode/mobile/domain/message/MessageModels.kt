package dev.opencode.mobile.domain.message

data class Message(
    val id: String,
    val role: MessageRole,
    val text: String,
    val model: String? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val mode: String? = null,
    val sentAtEpochMs: Long? = null,
    val contextInputTokens: Long? = null,
)

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
