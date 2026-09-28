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

    fun observeCatchUpChrome(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    fun observeCatchUpChrome(
        sessionId: String,
        directory: String? = null,
    ): Flow<Boolean> = observeCatchUpChrome()

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

    suspend fun loadOlderMessages(
        sessionId: String,
        directory: String? = null,
    ): OlderLoadResult = OlderLoadResult(messages = emptyList(), hasOlder = false, addedCount = 0)

    suspend fun loadOlderDisplayMessages(
        sessionId: String,
        directory: String? = null,
    ): OlderDisplayLoadResult {
        val result = loadOlderMessages(sessionId, directory)
        return OlderDisplayLoadResult(
            messages = result.messages.map(::DisplayMessage),
            hasOlder = result.hasOlder,
            addedCount = result.addedCount,
        )
    }

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
