package dev.opencode.mobile.domain.t3

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.DisplayMessageListUpdate
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageContentPreparer
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.message.MessageListUpdate
import dev.opencode.mobile.domain.message.MessageRepository
import dev.opencode.mobile.domain.message.PromptSendOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class T3MessageRepository
    @Inject
    constructor(
        private val runtime: T3Runtime,
        private val contentPreparer: MessageContentPreparer,
        private val uploader: T3AttachmentUploader,
    ) : MessageRepository {
        override fun observeMessages(
            sessionId: String,
            directory: String?,
        ): Flow<MessageListUpdate> =
            merge(
                runtime.messageLists
                    .map { lists -> lists[sessionId].orEmpty() }
                    .distinctUntilChanged()
                    .map<List<Message>, MessageListUpdate> { messages -> MessageListUpdate.Messages(messages) },
                runtime.threadFailures
                    .filter { failure -> failure.threadId == sessionId }
                    .map<T3ThreadFailure, MessageListUpdate> { failure -> MessageListUpdate.LoadFailed(failure.error) },
            ).onStart {
                    val loaded = runCatching { runtime.loadThreadMessages(sessionId) }.getOrElse { error ->
                        emit(MessageListUpdate.LoadFailed(error))
                        return@onStart
                    }
                    emit(MessageListUpdate.Messages(loaded))
                }

        override fun observeDisplayMessages(
            sessionId: String,
            directory: String?,
        ): Flow<DisplayMessageListUpdate> =
            observeMessages(sessionId, directory).transform { update ->
                emit(
                    when (update) {
                        is MessageListUpdate.Messages ->
                            DisplayMessageListUpdate.Messages(
                                messages = contentPreparer.prepareMessages(update.messages),
                                isFromCache = update.isFromCache,
                                hasOlder = update.hasOlder,
                            )
                        is MessageListUpdate.LoadFailed -> DisplayMessageListUpdate.LoadFailed(update.error)
                        is MessageListUpdate.CacheSyncConfirmed ->
                            DisplayMessageListUpdate.CacheSyncConfirmed(hasOlder = update.hasOlder)
                    },
                )
            }

        override suspend fun refreshMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = runtime.loadThreadMessages(sessionId)

        override suspend fun refreshDisplayMessages(
            sessionId: String,
            directory: String?,
        ): List<DisplayMessage> = contentPreparer.prepareMessages(refreshMessages(sessionId, directory))

        override suspend fun prepareOptimisticUserMessage(
            id: String,
            text: String,
        ): DisplayMessage = contentPreparer.prepareOptimisticUserMessage(id, text)

        override fun clearMessageCache() {
            runtime.clearTranscript()
        }

        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> {
            val cached = runtime.cachedMessages(sessionId)
            if (cached.isNotEmpty()) return cached
            return runtime.loadThreadMessages(sessionId)
        }

        override suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) {
            val thread = runtime.thread(sessionId)
            val model = thread?.modelSelection
            if (thread == null || model == null) {
                throw MessageError.Http(400, "This thread has no model selection.")
            }
            val running = T3Activity.isRunning(thread)
            val attachments =
                if (options.attachments.isEmpty()) {
                    emptyList()
                } else {
                    val profile = runtime.activeProfile() ?: throw T3RpcException("No active T3 environment.")
                    uploader.upload(profile, options.attachments)
                }
            runtime.dispatch(
                T3Commands.messageDispatch(
                    threadId = sessionId,
                    text = options.text,
                    modelSelection = model,
                    running = running,
                    attachments = attachments,
                ),
            )
        }
    }
