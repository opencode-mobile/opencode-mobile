package dev.opencode.mobile.domain.message

interface MessageRepository {
    suspend fun listMessages(
        sessionId: String,
        directory: String? = null,
    ): List<Message>

    suspend fun getMessage(
        sessionId: String,
        messageId: String,
        directory: String? = null,
    ): Message

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        directory: String? = null,
    ): Message

    suspend fun sendPromptAsync(
        sessionId: String,
        text: String,
        directory: String? = null,
    ) = sendPromptAsync(
        sessionId = sessionId,
        options = PromptSendOptions(text = text),
        directory = directory,
    )

    suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String? = null,
    )
}
