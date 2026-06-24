package dev.opencode.mobile.domain.live

import android.util.Log
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

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
        private val partOrders = ConcurrentHashMap<String, MutableMap<String, MutableList<String>>>()
        private val messageOrders = ConcurrentHashMap<String, MutableList<String>>()
        private val lastFingerprints = ConcurrentHashMap<String, String>()
        private val baseMessages = ConcurrentHashMap<String, List<Message>>()

        override fun clearCache() {
            cache.clear()
            infoCaches.clear()
            partsCaches.clear()
            partOrders.clear()
            messageOrders.clear()
            lastFingerprints.clear()
            baseMessages.clear()
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
                timelinePollFlow(sessionId, directory),
            )

        /**
         * Emits messages whenever SSE events mutate the local incremental cache.
         * On first subscription, does a full REST load to seed the cache.
         */
        private fun incrementalMessageFlow(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>> =
            channelFlow {
                logI("observeMessages subscribe session=$sessionId directory=$directory")
                // Initial seed: full REST load
                val initial = fullReload(sessionId, directory, reason = "initial-subscribe")
                if (initial != null) {
                    logI("observeMessages emit session=$sessionId count=${initial.size} source=initial-subscribe")
                    send(initial)
                }

                // React to SSE events
                eventStream.events.collect { event ->
                    if (event.type == "stream.reconnected" || event.type == "server.connected") {
                        val reloaded = fullReload(sessionId, directory, reason = event.type)
                        if (reloaded != null) {
                            logI("observeMessages emit session=$sessionId count=${reloaded.size} source=${event.type}")
                            send(reloaded)
                        }
                        return@collect
                    }

                    if (event.sessionId != sessionId) return@collect

                    when (event.type) {
                        "message.updated" -> handleMessageUpdated(event.rawProperties, sessionId)
                        "message.part.updated" -> {
                            if (requiresFullReloadForBaseOnlyPartUpdate(event.rawProperties, sessionId)) {
                                val reloaded = fullReload(sessionId, directory, reason = "message.part.updated-base-only")
                                if (reloaded != null) {
                                    logI(
                                        "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                            "source=message.part.updated-base-only",
                                    )
                                    send(reloaded)
                                }
                                return@collect
                            }
                            handleMessagePartUpdated(event.rawProperties, sessionId)
                        }
                        "message.removed" -> handleMessageRemoved(event.rawProperties, sessionId)
                        "message.part.removed" -> {
                            if (requiresFullReloadForBaseOnlyPartRemoval(event.rawProperties, sessionId)) {
                                val reloaded = fullReload(sessionId, directory, reason = "message.part.removed-base-only")
                                if (reloaded != null) {
                                    logI(
                                        "observeMessages emit session=$sessionId count=${reloaded.size} " +
                                            "source=message.part.removed-base-only",
                                    )
                                    send(reloaded)
                                }
                                return@collect
                            }
                            handleMessagePartRemoved(event.rawProperties, sessionId)
                        }
                        else -> return@collect
                    }

                    val messages = buildMessages(sessionId)
                    if (messages.isNotEmpty() && markChanged(sessionId, messages)) {
                        logD("observeMessages emit session=$sessionId count=${messages.size} source=sse-incremental type=${event.type}")
                        send(messages)
                    }
                }
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
            partOrders[sessionId]!!.getOrPut(messageId) { mutableListOf() }

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

            ensureCaches(sessionId)
            val msgParts = partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }
            val msgPartOrder = partOrders[sessionId]!!.getOrPut(messageId) { mutableListOf() }

            val resolvedPart = resolvePart(partDto, msgParts[partId], rawProperties["delta"]?.jsonPrimitive?.contentOrNull)
                ?: return

            if (partId !in msgPartOrder) {
                msgPartOrder.add(partId)
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
            partOrders[sessionId]?.remove(messageId)
            messageOrders[sessionId]?.removeAll { it == messageId }
        }

        private fun handleMessagePartRemoved(
            rawProperties: JsonObject?,
            sessionId: String,
        ) {
            val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return
            val partId = rawProperties?.get("partID")?.jsonPrimitive?.contentOrNull ?: return
            partsCaches[sessionId]?.get(messageId)?.remove(partId)
            partOrders[sessionId]?.get(messageId)?.removeAll { it == partId }
        }

        private fun requiresFullReloadForBaseOnlyPartUpdate(
            rawProperties: JsonObject?,
            sessionId: String,
        ): Boolean {
            val messageId =
                rawProperties
                    ?.get("part")
                    ?.jsonObject
                    ?.get("messageID")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: return false
            return isBaseOnlyMessage(sessionId, messageId)
        }

        private fun requiresFullReloadForBaseOnlyPartRemoval(
            rawProperties: JsonObject?,
            sessionId: String,
        ): Boolean {
            val messageId = rawProperties?.get("messageID")?.jsonPrimitive?.contentOrNull ?: return false
            return isBaseOnlyMessage(sessionId, messageId)
        }

        private fun isBaseOnlyMessage(
            sessionId: String,
            messageId: String,
        ): Boolean {
            val isInRestSnapshot = baseMessages[sessionId]?.any { it.id == messageId } == true
            val hasIncrementalInfo = infoCaches[sessionId]?.containsKey(messageId) == true
            return isInRestSnapshot && !hasIncrementalInfo
        }

        // MARK: Build messages from cache

        private fun buildMessages(sessionId: String): List<Message> {
            val infos = infoCaches[sessionId] ?: return emptyList()
            val parts = partsCaches[sessionId] ?: return emptyList()
            val orders = partOrders[sessionId] ?: emptyMap()
            val order = messageOrders[sessionId] ?: return emptyList()
            val base = baseMessages[sessionId].orEmpty()
            val baseById = base.associateBy { it.id }

            return order.mapNotNull { messageId ->
                val infoJson = infos[messageId] ?: return@mapNotNull baseById[messageId]
                val info = tryParseInfoDto(infoJson) ?: return@mapNotNull baseById[messageId]
                val messageParts = orderedParts(messageId, parts, orders)
                val message = adapter.toMessage(info, messageParts)
                when {
                    message.hasRenderableContent() -> message
                    else -> baseById[messageId]
                }
            }
        }

        // MARK: Full reload

        private suspend fun fullReload(
            sessionId: String,
            directory: String,
            reason: String,
        ): List<Message>? {
            var messages: List<Message>? = null
            val elapsedMs =
                measureTimeMillis {
                    messages =
                        runCatching {
                            messageRepository.listMessages(sessionId, directory)
                        }.getOrNull()
                }
            if (messages == null) {
                logW("fullReload failed session=$sessionId reason=$reason ms=$elapsedMs")
                return null
            }
            rebuildCache(sessionId, messages)
            val fp = messageFingerprint(messages)
            lastFingerprints[sessionId] = fp
            logI(
                "fullReload session=$sessionId reason=$reason count=${messages.size} ms=$elapsedMs",
            )
            return messages
        }

        private fun rebuildCache(
            sessionId: String,
            messages: List<Message>,
        ) {
            ensureCaches(sessionId)
            infoCaches[sessionId]!!.clear()
            partsCaches[sessionId]!!.clear()
            partOrders[sessionId]!!.clear()
            messageOrders[sessionId] = messages.map { it.id }.toMutableList()
            baseMessages[sessionId] = messages
        }

        // MARK: Poll loop

        private fun timelinePollFlow(
            sessionId: String,
            directory: String,
        ): Flow<List<Message>> =
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
                        logD("observeMessages emit session=$sessionId count=${messages.size} source=poll fingerprint-changed")
                        send(messages)
                    }
                }
            }

        // MARK: Helpers

        private fun ensureCaches(sessionId: String) {
            infoCaches.getOrPut(sessionId) { mutableMapOf() }
            partsCaches.getOrPut(sessionId) { mutableMapOf() }
            partOrders.getOrPut(sessionId) { mutableMapOf() }
            messageOrders.getOrPut(sessionId) { mutableListOf() }
        }

        private fun resolvePart(
            partDto: MessagePartDto,
            existingPart: MessagePart?,
            delta: String?,
        ): MessagePart? {
            if (delta != null && partDto.type == "text") {
                val existingText =
                    if (existingPart is MessagePart.Text) {
                        existingPart.text
                    } else {
                        partDto.text.orEmpty()
                    }
                return MessagePart.Text(text = existingText + delta, isStreaming = true)
            }
            return adapter.toMessagePart(partDto)
        }

        private fun orderedParts(
            messageId: String,
            parts: Map<String, Map<String, MessagePart>>,
            orders: Map<String, List<String>>,
        ): List<MessagePart> {
            val messageParts = parts[messageId] ?: return emptyList()
            val orderedIds = orders[messageId].orEmpty()
            val ordered = orderedIds.mapNotNull { messageParts[it] }
            val leftovers =
                messageParts
                    .filterKeys { it !in orderedIds }
                    .toSortedMap()
                    .values
                    .toList()
            return ordered + leftovers
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

        private fun markChanged(
            sessionId: String,
            messages: List<Message>,
        ): Boolean {
            val fp = messageFingerprint(messages)
            if (fp == lastFingerprints[sessionId]) return false
            lastFingerprints[sessionId] = fp
            return true
        }

        private fun Message.hasRenderableContent(): Boolean = text.isNotBlank() || parts.isNotEmpty()

        private fun logI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun logD(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        private fun logW(message: String) {
            runCatching { Log.w(TAG, message) }
        }

        private companion object {
            private const val TAG = "MessageLiveStore"
        }
    }
