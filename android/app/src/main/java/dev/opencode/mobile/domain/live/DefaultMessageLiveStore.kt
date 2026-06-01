package dev.opencode.mobile.domain.live

import dev.opencode.mobile.data.remote.dto.MessageDto
import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.domain.event.ForegroundEventStream
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        private val adapter = dev.opencode.mobile.domain.message.MessageDomainAdapter()
        private val json = Json { ignoreUnknownKeys = true }

        // Per-session incremental caches
        private val infoCaches = ConcurrentHashMap<String, MutableMap<String, JsonObject>>()
        private val partsCaches = ConcurrentHashMap<String, MutableMap<String, MutableMap<String, MessagePart>>>()
        private val messageOrders = ConcurrentHashMap<String, MutableList<String>>()
        private val lastFingerprints = ConcurrentHashMap<String, String>()

        override fun clearCache() {
            cache.clear()
            infoCaches.clear()
            partsCaches.clear()
            messageOrders.clear()
            lastFingerprints.clear()
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
            merge(
                incrementalMessageFlow(sessionId, directory),
                timelinePollFlow(sessionId, directory).map { fullReload(sessionId, directory) }.filterNotNull(),
            )

        /**
         * Emits messages whenever SSE events mutate the local incremental cache.
         * On first subscription, does a full REST load to seed the cache.
         */
        private fun incrementalMessageFlow(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>> {
            val mutable = MutableStateFlow<List<Message>>(emptyList())

            liveStoreScope.coroutineScope.launch {
                // Initial seed: full REST load
                val initial = fullReload(sessionId, directory)
                if (initial != null) {
                    mutable.emit(initial)
                }

                // React to SSE events
                eventStream.events.collect { event ->
                    if (event.type == "stream.reconnected" || event.type == "server.connected") {
                        val reloaded = fullReload(sessionId, directory)
                        if (reloaded != null) mutable.emit(reloaded)
                        return@collect
                    }

                    if (event.sessionId != sessionId) return@collect

                    when (event.type) {
                        "message.updated" -> handleMessageUpdated(event.rawProperties, sessionId)
                        "message.part.updated" -> handleMessagePartUpdated(event.rawProperties, sessionId)
                        "message.removed" -> handleMessageRemoved(event.rawProperties, sessionId)
                        "message.part.removed" -> handleMessagePartRemoved(event.rawProperties, sessionId)
                        else -> return@collect
                    }

                    val messages = buildMessages(sessionId)
                    if (messages.isNotEmpty()) {
                        mutable.emit(messages)
                    }
                }
            }

            return mutable.asSharedFlow()
        }

        // MARK: Event handlers

        private fun handleMessageUpdated(
            rawProperties: JsonObject?,
            sessionId: String,
        ) {
            val infoJson = rawProperties?.get("info")?.jsonObject ?: return
            val info = json.decodeFromString<MessageInfoDto>(infoJson.toString()) ?: return
            val messageId = info.id

            ensureCaches(sessionId)
            infoCaches[sessionId]!![messageId] = infoJson
            partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }

            val order = messageOrders[sessionId]!!
            if (messageId !in order) {
                order.add(messageId)
            }
        }

        private fun handleMessagePartUpdated(
            rawProperties: JsonObject?,
            sessionId: String,
        ) {
            val partJson = rawProperties?.get("part")?.jsonObject ?: return
            val partDto = tryParsePartDto(partJson) ?: return
            val messageId = partDto.messageId ?: return
            val partId = partDto.id ?: return
            val domainPart = adapter.toMessagePart(partDto) ?: return

            ensureCaches(sessionId)
            val msgParts = partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }

            // Mark as streaming if delta is present
            val resolvedPart =
                if (domainPart is MessagePart.Text && rawProperties.containsKey("delta")) {
                    MessagePart.Text(text = domainPart.text, isStreaming = true)
                } else {
                    domainPart
                }

            msgParts[partId] = resolvedPart
        }

        private fun handleMessageRemoved(
            rawProperties: JsonObject?,
            sessionId: String,
        ) {
            val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return
            infoCaches[sessionId]?.remove(messageId)
            partsCaches[sessionId]?.remove(messageId)
            messageOrders[sessionId]?.removeAll { it == messageId }
        }

        private fun handleMessagePartRemoved(
            rawProperties: JsonObject?,
            sessionId: String,
        ) {
            val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return
            val partId = rawProperties?.get("partID")?.jsonPrimitive?.contentOrNull ?: return
            partsCaches[sessionId]?.get(messageId)?.remove(partId)
        }

        // MARK: Build messages from cache

        private fun buildMessages(sessionId: String): List<Message> {
            val infos = infoCaches[sessionId] ?: return emptyList()
            val parts = partsCaches[sessionId] ?: return emptyList<Message>()
            val order = messageOrders[sessionId] ?: return emptyList()

            return order.mapNotNull { messageId ->
                val infoJson = infos[messageId] ?: return@mapNotNull null
                val info =
                    tryParseInfoDto(infoJson) ?: return@mapNotNull null
                val messageParts = parts[messageId]?.values?.toList() ?: emptyList()
                adapter.toMessage(info, messageParts)
            }
        }

        // MARK: Full reload

        private suspend fun fullReload(
            sessionId: String,
            directory: String,
        ): List<Message>? {
            val messages =
                runCatching {
                    messageRepository.listMessages(sessionId, directory)
                }.getOrNull() ?: return null

            rebuildCache(sessionId, messages)
            val fp = messageFingerprint(messages)
            lastFingerprints[sessionId] = fp
            return messages
        }

        private fun rebuildCache(
            sessionId: String,
            messages: List<Message>,
        ) {
            ensureCaches(sessionId)
            infoCaches[sessionId]!!.clear()
            partsCaches[sessionId]!!.clear()
            messageOrders[sessionId] = messages.map { it.id }.toMutableList()
        }

        // MARK: Poll loop

        private fun timelinePollFlow(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>?> =
            channelFlow {
                while (true) {
                    delay(LiveStoreRefreshPolicy.WORKING_POLL_INTERVAL_MS)
                    val messages =
                        runCatching { messageRepository.listMessages(sessionId, directory) }.getOrNull()
                            ?: continue
                    val fp = messageFingerprint(messages)
                    if (fp != lastFingerprints[sessionId]) {
                        rebuildCache(sessionId, messages)
                        lastFingerprints[sessionId] = fp
                        send(messages)
                    }
                }
            }

        // MARK: Helpers

        private fun ensureCaches(sessionId: String) {
            infoCaches.getOrPut(sessionId) { mutableMapOf() }
            partsCaches.getOrPut(sessionId) { mutableMapOf() }
            messageOrders.getOrPut(sessionId) { mutableListOf() }
        }

        private fun tryParsePartDto(json: JsonObject): MessagePartDto? =
            runCatching {
                this@DefaultMessageLiveStore.json.decodeFromJsonElement<MessagePartDto>(json)
            }.getOrNull()

        private fun tryParseInfoDto(json: JsonObject): MessageInfoDto? =
            runCatching {
                this@DefaultMessageLiveStore.json.decodeFromJsonElement<MessageInfoDto>(json)
            }.getOrNull()

        private fun messageFingerprint(messages: List<Message>): String =
            messages.joinToString("|") { "${it.id}:${it.text.length}:${it.text.hashCode()}" }
    }
