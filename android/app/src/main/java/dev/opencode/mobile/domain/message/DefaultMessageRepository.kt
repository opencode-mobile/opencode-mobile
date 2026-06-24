package dev.opencode.mobile.domain.message

import android.util.Log
import dev.opencode.mobile.data.remote.MessagesApi
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class DefaultMessageRepository
    @Inject
    constructor(
        private val api: MessagesApi,
        private val adapter: MessageDomainAdapter,
    ) : MessageRepository {
        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> {
            var messages: List<Message> = emptyList()
            val elapsedMs =
                measureTimeMillis {
                    messages =
                        runCatching { api.listMessages(sessionId, directory).map(adapter::toMessage) }
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
            runCatching { api.sendPromptAsync(sessionId, adapter.toSendRequest(options), directory) }
                .getOrElse { throw adapter.mapError(it) }
        }

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private companion object {
            private const val TAG = "MessageRepo"
        }
    }
