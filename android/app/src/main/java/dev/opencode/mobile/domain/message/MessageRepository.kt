package dev.opencode.mobile.domain.message

import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeMessages(
        sessionId: String,
        directory: String? = null,
    ): Flow<MessageListUpdate>

    fun observeDisplayMessages(
        sessionId: String,
        directory: String? = null,
    ): Flow<DisplayMessageListUpdate>

    suspend fun refreshMessages(
        sessionId: String,
        directory: String? = null,
    ): List<Message>

    suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String? = null,
    ): List<DisplayMessage>

    suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
    ): DisplayMessage

    suspend fun prepareOptimisticUserMessage(
        id: String,
        text: String,
        attachments: List<Attachment>,
    ): DisplayMessage = prepareOptimisticUserMessage(id, text)

    fun clearMessageCache()

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
