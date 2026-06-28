package dev.opencode.mobile.domain.message

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

private const val STREAMING_PREPARE_DEBOUNCE_MS = 40L

class DisplayMessagePipeline(
    private val messageSync: MessageSync,
    private val contentPreparer: MessageContentPreparer,
) {
    fun observeDisplayMessages(
        sessionId: String,
        directory: String?,
    ): Flow<DisplayMessageListUpdate> =
        flow {
            var previousById = emptyMap<String, DisplayMessage>()
            messageSync.observeMessages(sessionId, directory).collect { update ->
                when (update) {
                    is MessageListUpdate.LoadFailed -> {
                        emit(DisplayMessageListUpdate.LoadFailed(update.error))
                    }
                    is MessageListUpdate.Messages -> {
                        if (hasStreamingDelta(update.messages)) {
                            delay(STREAMING_PREPARE_DEBOUNCE_MS.milliseconds)
                        }
                        val prepared = contentPreparer.prepareMessages(update.messages, previousById)
                        previousById = prepared.associateBy { it.id }
                        emit(DisplayMessageListUpdate.Messages(prepared))
                    }
                }
            }
        }

    suspend fun refreshDisplayMessages(
        sessionId: String,
        directory: String?,
    ): List<DisplayMessage> {
        val messages = messageSync.refreshMessages(sessionId, directory)
        return contentPreparer.prepareMessages(messages)
    }

    private fun hasStreamingDelta(messages: List<Message>): Boolean =
        messages.any { message ->
            message.parts.any { part -> part is MessagePart.Text && part.isStreaming }
        }
}
