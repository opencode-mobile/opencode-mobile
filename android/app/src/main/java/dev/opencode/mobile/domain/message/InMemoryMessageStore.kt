package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryMessageStore
    @Inject
    constructor(
        private val adapter: MessageDomainAdapter,
    ) : MessageStore {
        private val json = Json { ignoreUnknownKeys = true }

        private val infoCaches = ConcurrentHashMap<String, MutableMap<String, JsonObject>>()
        private val partsCaches = ConcurrentHashMap<String, MutableMap<String, MutableMap<String, MessagePart>>>()
        private val partOrders = ConcurrentHashMap<String, MutableMap<String, MutableList<String>>>()
        private val ignoredPartIds = ConcurrentHashMap<String, MutableMap<String, MutableSet<String>>>()
        private val messageOrders = ConcurrentHashMap<String, MutableList<String>>()
        private val baseMessages = ConcurrentHashMap<String, List<Message>>()
        private val revisions = ConcurrentHashMap<String, Long>()

        @Synchronized
        override fun replaceAll(
            sessionId: String,
            messages: List<Message>,
        ) {
            replaceAllLocked(sessionId, messages)
        }

        private fun replaceAllLocked(
            sessionId: String,
            messages: List<Message>,
        ) {
            ensureCaches(sessionId)
            infoCaches[sessionId]!!.clear()
            partsCaches[sessionId]!!.clear()
            partOrders[sessionId]!!.clear()
            ignoredPartIds[sessionId]!!.clear()
            messageOrders[sessionId] = messages.map { it.id }.toMutableList()
            baseMessages[sessionId] = messages
            bumpRevision(sessionId)
        }

        @Synchronized
        override fun apply(
            sessionId: String,
            patch: MessagePatch,
        ) {
            when (patch) {
                is MessagePatch.InfoUpdated -> applyInfoUpdated(sessionId, patch.infoJson)
                is MessagePatch.PartUpdated -> applyPartUpdated(sessionId, patch.partJson, patch.delta)
                is MessagePatch.PartDelta -> applyPartDelta(sessionId, patch.messageId, patch.partId, patch.delta)
                is MessagePatch.Removed -> applyMessageRemoved(sessionId, patch.messageId)
                is MessagePatch.PartRemoved -> applyPartRemoved(sessionId, patch.messageId, patch.partId)
            }
            bumpRevision(sessionId)
        }

        @Synchronized
        override fun messages(sessionId: String): List<Message> {
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

        @Synchronized
        override fun isRestSeededMessage(
            sessionId: String,
            messageId: String,
        ): Boolean = baseMessages[sessionId]?.any { it.id == messageId } == true

        @Synchronized
        override fun hasStreamingText(sessionId: String): Boolean {
            val sessionParts = partsCaches[sessionId] ?: return false
            return sessionParts.values.any { messageParts ->
                messageParts.values.any { part -> part is MessagePart.Text && part.isStreaming }
            }
        }

        @Synchronized
        override fun revision(sessionId: String): Long = revisions[sessionId] ?: 0L

        @Synchronized
        override fun replaceAllIfRevision(
            sessionId: String,
            messages: List<Message>,
            expectedRevision: Long,
        ): Boolean {
            if (revision(sessionId) != expectedRevision) return false
            replaceAllLocked(sessionId, messages)
            return true
        }

        @Synchronized
        override fun finishStreamingText(sessionId: String) {
            var changed = false
            for (messageParts in partsCaches[sessionId]?.values.orEmpty()) {
                for (entry in messageParts.entries) {
                    val text = entry.value as? MessagePart.Text ?: continue
                    if (text.isStreaming) {
                        entry.setValue(text.copy(isStreaming = false))
                        changed = true
                    }
                }
            }
            if (changed) bumpRevision(sessionId)
        }

        @Synchronized
        override fun clear() {
            infoCaches.clear()
            partsCaches.clear()
            partOrders.clear()
            ignoredPartIds.clear()
            messageOrders.clear()
            baseMessages.clear()
            revisions.clear()
        }

        private fun applyInfoUpdated(
            sessionId: String,
            infoJson: JsonObject,
        ) {
            val info = json.decodeFromJsonElement<MessageInfoDto>(infoJson)
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

        private fun applyPartUpdated(
            sessionId: String,
            partJson: JsonObject,
            delta: String?,
        ) {
            val partDto = tryParsePartDto(partJson) ?: return
            val messageId = partDto.messageId ?: return
            val partId = partDto.id ?: return

            ensureCaches(sessionId)
            val msgParts = partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }
            val msgPartOrder = partOrders[sessionId]!!.getOrPut(messageId) { mutableListOf() }

            val resolvedPart = resolvePart(partDto, msgParts[partId], delta)
            if (resolvedPart == null) {
                // Reasoning/step/etc. — never promote their deltas into assistant text.
                ignoredPartIds[sessionId]!!.getOrPut(messageId) { mutableSetOf() }.add(partId)
                msgParts.remove(partId)
                msgPartOrder.removeAll { it == partId }
                return
            }

            ignoredPartIds[sessionId]?.get(messageId)?.remove(partId)
            if (partId !in msgPartOrder) {
                msgPartOrder.add(partId)
            }
            msgParts[partId] = resolvedPart
        }

        private fun applyPartDelta(
            sessionId: String,
            messageId: String,
            partId: String,
            delta: String,
        ) {
            if (ignoredPartIds[sessionId]?.get(messageId)?.contains(partId) == true) return
            val msgParts = partsCaches[sessionId]?.get(messageId) ?: return
            val existing = msgParts[partId] as? MessagePart.Text ?: return
            msgParts[partId] = MessagePart.Text(text = existing.text + delta, isStreaming = true)
        }

        private fun applyMessageRemoved(
            sessionId: String,
            messageId: String,
        ) {
            infoCaches[sessionId]?.remove(messageId)
            partsCaches[sessionId]?.remove(messageId)
            partOrders[sessionId]?.remove(messageId)
            ignoredPartIds[sessionId]?.remove(messageId)
            messageOrders[sessionId]?.removeAll { it == messageId }
        }

        private fun applyPartRemoved(
            sessionId: String,
            messageId: String,
            partId: String,
        ) {
            partsCaches[sessionId]?.get(messageId)?.remove(partId)
            partOrders[sessionId]?.get(messageId)?.removeAll { it == partId }
            ignoredPartIds[sessionId]?.get(messageId)?.remove(partId)
            if (ignoredPartIds[sessionId]?.get(messageId)?.isEmpty() == true) {
                ignoredPartIds[sessionId]?.remove(messageId)
            }
        }

        private fun ensureCaches(sessionId: String) {
            infoCaches.getOrPut(sessionId) { mutableMapOf() }
            partsCaches.getOrPut(sessionId) { mutableMapOf() }
            partOrders.getOrPut(sessionId) { mutableMapOf() }
            ignoredPartIds.getOrPut(sessionId) { mutableMapOf() }
            messageOrders.getOrPut(sessionId) { mutableListOf() }
        }

        private fun bumpRevision(sessionId: String) {
            revisions[sessionId] = (revisions[sessionId] ?: 0L) + 1L
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

        private fun tryParsePartDto(jsonObject: JsonObject): MessagePartDto? =
            runCatching {
                json.decodeFromJsonElement<MessagePartDto>(jsonObject)
            }.getOrNull()

        private fun tryParseInfoDto(jsonObject: JsonObject): MessageInfoDto? =
            runCatching {
                json.decodeFromJsonElement<MessageInfoDto>(jsonObject)
            }.getOrNull()

        private fun Message.hasRenderableContent(): Boolean = text.isNotBlank() || parts.isNotEmpty()
    }
