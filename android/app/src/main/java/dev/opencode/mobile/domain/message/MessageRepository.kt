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
    )
}
