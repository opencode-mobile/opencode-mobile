package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.data.remote.MessagesApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class DefaultMessageRepository
    @Inject
    constructor(
        private val api: MessagesApi,
        private val adapter: MessageDomainAdapter,
        private val messageSync: MessageSync,
        private val displayMessagePipeline: DisplayMessagePipeline,
        private val contentPreparer: MessageContentPreparer,
        private val messageRemote: MessageRemote,
    ) : MessageRepository {
        override fun observeMessages(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> = messageSync.observeMessages(sessionId, directory)

        override fun observeDisplayMessages(
            sessionId: String,
            directory: String?,
        ): Flow<DisplayMessageListUpdate> = displayMessagePipeline.observeDisplayMessages(sessionId, directory)

        override suspend fun refreshMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = messageSync.refreshMessages(sessionId, directory)

        override suspend fun refreshDisplayMessages(
            sessionId: String,
            directory: String?,
        ): List<DisplayMessage> = displayMessagePipeline.refreshDisplayMessages(sessionId, directory)

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
        ): DisplayMessage = contentPreparer.prepareOptimisticUserMessage(id, text)

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
            attachments: List<Attachment>,
        ): DisplayMessage = contentPreparer.prepareOptimisticUserMessage(id, text, attachments)

        override fun clearMessageCache() {
            messageSync.clearCache()
        }

        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> {
            var messages: List<Message> = emptyList()
            val elapsedMs =
                measureTimeMillis {
                    messages =
                        runCatching { messageRemote.listMessages(sessionId, directory) }
                            .getOrElse { throw adapter.mapError(it) }
                }
            logI(
                "listMessages session=$sessionId directory=${directory ?: "-"} " +
                    "count=${messages.size} ms=$elapsedMs",
            )
            return messages
        }

        override suspend fun getMessage(
            sessionId: String,
            messageId: String,
            directory: String?,
        ): Message =
            runCatching { adapter.toMessage(api.getMessage(sessionId, messageId, directory)) }
                .getOrElse { throw adapter.mapError(it) }

        override suspend fun sendMessage(
            sessionId: String,
            text: String,
            directory: String?,
        ): Message =
            runCatching {
                adapter.toMessage(api.sendMessage(sessionId, adapter.toSendRequest(text), directory))
            }.getOrElse { throw adapter.mapError(it) }

        override suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) {
            logI(
                "sendPromptAsync session=$sessionId directory=${directory ?: "-"} " +
                    "text=${options.text.isNotBlank()} files=${options.attachments.size}",
            )
            runCatching { messageRemote.sendPromptAsync(sessionId, options, directory) }
                .getOrElse { throw adapter.mapError(it) }
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private companion object {
            private const val TAG = "MessageRepo"
        }
    }
