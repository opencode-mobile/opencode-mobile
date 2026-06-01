package dev.opencode.mobile.domain.live

import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class DefaultMessageLiveStore
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val eventStream: ForegroundEventStream,
        private val liveStoreScope: LiveStoreScope,
    ) : MessageLiveStore {
        private val cache = ConcurrentHashMap<MessageObservationKey, SharedFlow<List<Message>>>()

        override fun clearCache() {
            cache.clear()
        }

        override fun observeMessages(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>> =
            cache.getOrPut(MessageObservationKey(sessionId, directory)) {
                buildSharedFlow(sessionId, directory)
            }

        private fun buildSharedFlow(
            sessionId: String,
            directory: String,
        ): SharedFlow<List<Message>> =
            buildMessageFlow(sessionId, directory)
                .shareIn(
                    scope = liveStoreScope.coroutineScope,
                    started = SharingStarted.WhileSubscribed(LiveStoreRefreshPolicy.SUBSCRIBER_STOP_TIMEOUT_MS),
                    replay = 1,
                )

        private fun buildMessageFlow(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>> =
            channelFlow {
                suspend fun loadMessages(): List<Message>? =
                    runCatching {
                        messageRepository.listMessages(sessionId, directory)
                    }.getOrNull()

                val initialMessages = loadMessages()
                val initialFingerprint = initialMessages?.let(::messageFingerprint)
                initialMessages?.let { send(it) }

                merge(
                    eventStream.events
                        .filter { event ->
                            event.type == "stream.reconnected" ||
                                (event.sessionId == sessionId && event.triggersMessageRefresh)
                        }.map { },
                    timelinePollFlow(sessionId, directory, initialFingerprint),
                ).debounce(LiveStoreRefreshPolicy.MESSAGE_DEBOUNCE_MS)
                    .collect {
                        loadMessages()?.let { send(it) }
                    }
            }

        private fun timelinePollFlow(
            sessionId: String,
            directory: String,
            initialFingerprint: String?,
        ): Flow<Unit> =
            channelFlow {
                var lastFingerprint = initialFingerprint
                while (true) {
                    delay(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS)
                    val messages =
                        runCatching { messageRepository.listMessages(sessionId, directory) }.getOrNull()
                            ?: continue
                    val fingerprint = messageFingerprint(messages)
                    if (fingerprint != lastFingerprint) {
                        lastFingerprint = fingerprint
                        send(Unit)
                    }
                }
            }

        private fun messageFingerprint(messages: List<Message>): String =
            messages.joinToString("|") { "${it.id}:${it.text.length}:${it.text.hashCode()}" }
    }
