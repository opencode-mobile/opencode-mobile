package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.dto.MessageInfoDto
import dev.opencode.mobile.data.remote.dto.MessagePartDto
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

        /** Pending text delta pieces keyed by session → "messageId\\upartId". */
        private val deltaChunks = ConcurrentHashMap<String, MutableMap<String, MutableList<String>>>()

        /**
         * How many characters of the current base text have been matched by late
         * replay deltas after a snapshot, keyed like [deltaChunks].
         */
        private val absorbedReplayChars = ConcurrentHashMap<String, MutableMap<String, Int>>()

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
            val previousStarts = toolStartTimesLocked(sessionId)
            infoCaches[sessionId]!!.clear()
            partsCaches[sessionId]!!.clear()
            partOrders[sessionId]!!.clear()
            ignoredPartIds[sessionId]!!.clear()
            deltaChunks[sessionId]!!.clear()
            absorbedReplayChars[sessionId]!!.clear()
            messageOrders[sessionId] = messages.map { it.id }.toMutableList()
            baseMessages[sessionId] = messages
            seedPartIdentities(sessionId, messages, previousStarts)
            bumpRevision(sessionId)
        }

        @Synchronized
        override fun apply(
            sessionId: String,
            patch: MessagePatch,
        ): MessageStoreApplyResult {
            val mutation =
                when (patch) {
                    is MessagePatch.InfoUpdated -> applyInfoUpdated(sessionId, patch.infoJson)
                    is MessagePatch.PartUpdated -> applyPartUpdated(sessionId, patch.partJson, patch.delta)
                    is MessagePatch.PartDelta -> applyPartDelta(sessionId, patch.messageId, patch.partId, patch.delta)
                    is MessagePatch.Removed -> applyMessageRemoved(sessionId, patch.messageId)
                    is MessagePatch.PartRemoved -> applyPartRemoved(sessionId, patch.messageId, patch.partId)
                } ?: return MessageStoreApplyResult.NoChange
            bumpRevision(sessionId)
            return MessageStoreApplyResult.Changed(mutation.copy(sessionRevision = revision(sessionId)))
        }

        @Synchronized
        override fun messages(sessionId: String): List<Message> {
            val infos = infoCaches[sessionId] ?: return emptyList()
            val parts = partsCaches[sessionId] ?: return emptyList()
            val orders = partOrders[sessionId] ?: emptyMap()
            val order = messageOrders[sessionId] ?: return emptyList()
            val base = baseMessages[sessionId].orEmpty()
            val baseById = base.associateBy { it.id }

            return order.mapNotNull { messageId -> rebuildMessage(sessionId, messageId, infos, parts, orders, baseById) }
        }

        @Synchronized
        fun message(
            sessionId: String,
            messageId: String,
        ): Message? {
            val infos = infoCaches[sessionId] ?: emptyMap()
            val parts = partsCaches[sessionId] ?: emptyMap()
            val orders = partOrders[sessionId] ?: emptyMap()
            val order = messageOrders[sessionId] ?: return null
            if (messageId !in order) return null
            val baseById = baseMessages[sessionId].orEmpty().associateBy { it.id }
            return rebuildMessage(sessionId, messageId, infos, parts, orders, baseById)
        }

        private fun rebuildMessage(
            sessionId: String,
            messageId: String,
            infos: Map<String, JsonObject>,
            parts: Map<String, Map<String, MessagePart>>,
            orders: Map<String, List<String>>,
            baseById: Map<String, Message>,
        ): Message? {
            val infoJson = infos[messageId]
            if (infoJson != null) {
                val info = tryParseInfoDto(infoJson) ?: return baseById[messageId]
                val messageParts = orderedParts(sessionId, messageId, parts, orders)
                val message = adapter.toMessage(info, messageParts)
                return when {
                    message.hasRenderableContent() -> message
                    else -> baseById[messageId]
                }
            }
            val baseMessage = baseById[messageId] ?: return null
            val liveParts = orderedParts(sessionId, messageId, parts, orders)
            if (liveParts.isEmpty()) return baseMessage
            val flat = adapter.flattenedText(liveParts)
            return baseMessage.copy(
                parts = liveParts,
                text = flat.ifBlank { baseMessage.text },
            )
        }

        @Synchronized
        override fun isRestSeededMessage(
            sessionId: String,
            messageId: String,
        ): Boolean =
            baseMessages[sessionId]
                ?.firstOrNull { it.id == messageId }
                ?.parts
                ?.any { it.stablePartId() == null } == true

        @Synchronized
        override fun hasStreamingText(sessionId: String): Boolean {
            val sessionParts = partsCaches[sessionId] ?: return false
            return sessionParts.any { (messageId, messageParts) ->
                messageParts.any { (partId, part) ->
                    (part is MessagePart.Text || part is MessagePart.Reasoning) &&
                        (part.isStreamingPart() || hasChunks(sessionId, messageId, partId))
                }
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
        override fun mergeAuthoritativeIfRevision(
            sessionId: String,
            messages: List<Message>,
            expectedRevision: Long,
        ): Boolean {
            if (revision(sessionId) != expectedRevision) return false
            mergeAuthoritativeLocked(sessionId, messages)
            return true
        }

        @Synchronized
        override fun applyTailIfRevision(
            sessionId: String,
            tail: List<Message>,
            expectedRevision: Long,
        ): Boolean {
            if (revision(sessionId) != expectedRevision) return false
            val local = messages(sessionId)
            val merged = MessageWindowMerge.applyTail(local, tail)
            replaceAllLocked(sessionId, merged)
            return true
        }

        @Synchronized
        override fun mergeTailAuthoritativeIfRevision(
            sessionId: String,
            tail: List<Message>,
            expectedRevision: Long,
        ): Boolean {
            if (revision(sessionId) != expectedRevision) return false
            val local = messages(sessionId)
            val merged = MessageWindowMerge.applyTail(local, tail)
            mergeAuthoritativeLocked(sessionId, merged)
            return true
        }

        @Synchronized
        override fun prependOlderIfRevision(
            sessionId: String,
            older: List<Message>,
            expectedRevision: Long,
        ): Boolean {
            if (revision(sessionId) != expectedRevision) return false
            prependOlderLocked(sessionId, older)
            return true
        }

        @Synchronized
        override fun finishStreamingText(sessionId: String) {
            var changed = false
            for ((messageId, messageParts) in partsCaches[sessionId].orEmpty()) {
                for (entry in messageParts.entries) {
                    when (val part = entry.value) {
                        is MessagePart.Text -> {
                            val flattened = flattenedText(sessionId, messageId, part)
                            val wasStreaming = part.isStreaming || hasChunks(sessionId, messageId, part.id)
                            if (wasStreaming || flattened != part.text) {
                                entry.setValue(part.copy(text = flattened, isStreaming = false))
                                clearChunks(sessionId, messageId, part.id)
                                changed = true
                            }
                        }
                        is MessagePart.Reasoning -> {
                            val flattened =
                                flattenedText(sessionId, messageId, partId = part.id, baseText = part.text)
                            val wasStreaming = part.isStreaming || hasChunks(sessionId, messageId, part.id)
                            if (wasStreaming || flattened != part.text) {
                                entry.setValue(part.copy(text = flattened, isStreaming = false))
                                clearChunks(sessionId, messageId, part.id)
                                changed = true
                            }
                        }
                        else -> Unit
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
            deltaChunks.clear()
            absorbedReplayChars.clear()
        }

        private fun applyInfoUpdated(
            sessionId: String,
            infoJson: JsonObject,
        ): SessionChangeSet? {
            val info = runCatching { json.decodeFromJsonElement<MessageInfoDto>(infoJson) }.getOrNull() ?: return null
            val messageId = info.id

            ensureCaches(sessionId)
            val order = messageOrders[sessionId]!!
            val inserted = messageId !in order
            val previous = infoCaches[sessionId]!![messageId]
            // v2 infos are partial projections: keep fields an update omits.
            val priorTime = previous?.get("time") ?: baseTimeJson(sessionId, messageId)
            val merged =
                buildJsonObject {
                    infoJson.forEach { (key, value) -> put(key, value) }
                    if (!infoJson.containsKey("time")) {
                        priorTime?.let { put("time", it) }
                    }
                    if (previous != null && !infoJson.containsKey("waiting")) {
                        previous["waiting"]?.let { put("waiting", it) }
                    }
                    if (previous != null && !infoJson.containsKey("delivery")) {
                        previous["delivery"]?.let { put("delivery", it) }
                    }
                }
            infoCaches[sessionId]!![messageId] = merged
            partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }
            partOrders[sessionId]!!.getOrPut(messageId) { mutableListOf() }
            val isUserInfo = info.role.equals("user", ignoreCase = true)
            val waiting = merged.isWaiting()
            // A queued v2 prompt is hidden until delivered, then it belongs after
            // whatever the session produced meanwhile, not where it was enqueued.
            val delivered = !inserted && isUserInfo && previous?.isWaiting() == true && !waiting
            if (inserted && isUserInfo && waiting) {
                order.add(messageId)
            } else if (inserted) {
                insertMessage(sessionId, order, messageId, info.role)
            } else if (delivered) {
                order.remove(messageId)
                insertMessage(sessionId, order, messageId, info.role)
            }
            return SessionChangeSet(
                sessionRevision = 0,
                insertedMessageIds = if (inserted) setOf(messageId) else emptySet(),
                changedMessageIds = if (inserted) emptySet() else setOf(messageId),
                structuralChange = inserted || delivered,
            )
        }

        private fun JsonObject.isWaiting(): Boolean = (this["waiting"] as? JsonPrimitive)?.booleanOrNull == true

        /** REST-seeded rows have no cached info; carry their times into the first live info. */
        private fun baseTimeJson(
            sessionId: String,
            messageId: String,
        ): JsonObject? {
            val base = baseMessages[sessionId]?.firstOrNull { it.id == messageId } ?: return null
            val created = base.sentAtEpochMs ?: return null
            return buildJsonObject {
                put("created", created)
                base.completedAtEpochMs?.let { put("completed", it) }
            }
        }

        private fun insertMessage(
            sessionId: String,
            order: MutableList<String>,
            messageId: String,
            role: String,
        ) {
            if (role.equals("user", ignoreCase = true)) {
                val index = order.indexOfFirst { isTrailingLiveAssistant(sessionId, it) }
                val precededByUser = index > 0 && isUser(sessionId, order[index - 1])
                if (index >= 0 && !precededByUser) {
                    order.add(index, messageId)
                    return
                }
            }
            order.add(messageId)
        }

        private fun isUser(
            sessionId: String,
            messageId: String,
        ): Boolean {
            val infoRole =
                infoCaches[sessionId]
                    ?.get(messageId)
                    ?.get("role")
                    ?.jsonPrimitive
                    ?.contentOrNull
            if (infoRole.equals("user", ignoreCase = true)) return true
            return baseMessages[sessionId]?.firstOrNull { it.id == messageId }?.role == MessageRole.User
        }

        private fun isTrailingLiveAssistant(
            sessionId: String,
            messageId: String,
        ): Boolean {
            val base = baseMessages[sessionId]?.firstOrNull { it.id == messageId }
            val infoRole =
                infoCaches[sessionId]?.get(messageId)?.let { info ->
                    info["role"]?.jsonPrimitive?.contentOrNull
                }
            val isAssistant =
                infoRole.equals("assistant", ignoreCase = true) ||
                    base?.role == MessageRole.Assistant
            if (!isAssistant) return false
            val liveParts = partsCaches[sessionId]?.get(messageId)?.values.orEmpty()
            val parts = liveParts.ifEmpty { base?.parts.orEmpty() }
            if (parts.any { part ->
                    part is MessagePart.Text &&
                        (part.isStreaming || hasChunks(sessionId, messageId, part.id))
                }
            ) {
                return true
            }
            return parts.isEmpty() || parts.all { it is MessagePart.Text && it.text.isBlank() }
        }

        private fun applyPartUpdated(
            sessionId: String,
            partJson: JsonObject,
            delta: String?,
        ): SessionChangeSet? {
            val partDto = tryParsePartDto(partJson) ?: return null
            val messageId = partDto.messageId ?: return null
            val partId = partDto.id ?: return null

            ensureCaches(sessionId)
            val msgParts = partsCaches[sessionId]!!.getOrPut(messageId) { mutableMapOf() }
            val msgPartOrder = partOrders[sessionId]!!.getOrPut(messageId) { mutableListOf() }

            val resolvedPart = resolvePart(sessionId, messageId, partDto, msgParts[partId], delta)
            if (resolvedPart == null) {
                val hadPart = msgParts.containsKey(partId)
                ignoredPartIds[sessionId]!!.getOrPut(messageId) { mutableSetOf() }.add(partId)
                msgParts.remove(partId)
                msgPartOrder.removeAll { it == partId }
                clearChunks(sessionId, messageId, partId)
                if (!hadPart) return null
                return SessionChangeSet(
                    sessionRevision = 0,
                    changedMessageIds = setOf(messageId),
                    changedPartIdsByMessage = mapOf(messageId to setOf(partId)),
                    structuralChange = true,
                )
            }

            ignoredPartIds[sessionId]?.get(messageId)?.remove(partId)
            val insertedPart = partId !in msgPartOrder
            if (insertedPart) {
                msgPartOrder.add(partId)
            }
            val previous = msgParts[partId]
            val stored = mergePart(previous, resolvedPart)
            msgParts[partId] = stored
            logReasoningOverlay(
                sessionId = sessionId,
                messageId = messageId,
                partId = partId,
                previous = previous,
                stored = stored,
                inserted = insertedPart,
            )
            if (delta == null && replacesStreamSnapshot(resolvedPart)) {
                clearChunks(sessionId, messageId, partId)
            }
            val streamingChanged = previous?.isStreamingPart() != stored.isStreamingPart()
            return SessionChangeSet(
                sessionRevision = 0,
                changedMessageIds = setOf(messageId),
                changedPartIdsByMessage = mapOf(messageId to setOf(partId)),
                streamingStateChangedIds = if (streamingChanged) setOf(messageId) else emptySet(),
                structuralChange = insertedPart,
            )
        }

        private fun applyPartDelta(
            sessionId: String,
            messageId: String,
            partId: String,
            delta: String,
        ): SessionChangeSet? {
            if (ignoredPartIds[sessionId]?.get(messageId)?.contains(partId) == true) return null
            val msgParts = partsCaches[sessionId]?.get(messageId) ?: return null
            val existing = msgParts[partId] ?: return null
            if (existing is MessagePart.Tool) {
                val previous =
                    existing.input
                        ?.get("preview")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                msgParts[partId] =
                    existing.copy(
                        input =
                            buildJsonObject {
                                existing.input?.forEach { (key, value) -> if (key != "preview") put(key, value) }
                                put("preview", previous + delta)
                            },
                    )
                return SessionChangeSet(
                    sessionRevision = 0,
                    changedMessageIds = setOf(messageId),
                    changedPartIdsByMessage = mapOf(messageId to setOf(partId)),
                )
            }
            val baseText =
                when (existing) {
                    is MessagePart.Text -> existing.text
                    is MessagePart.Reasoning -> existing.text
                    else -> return null
                }
            val appended = appendChunk(sessionId, messageId, partId, baseText, delta)
            if (!appended) return null
            val wasStreaming = existing.isStreamingPart()
            if (!wasStreaming) {
                msgParts[partId] =
                    when (existing) {
                        is MessagePart.Text -> existing.copy(isStreaming = true)
                        is MessagePart.Reasoning -> existing.copy(isStreaming = true)
                        else -> existing
                    }
            }
            return SessionChangeSet(
                sessionRevision = 0,
                changedMessageIds = setOf(messageId),
                changedPartIdsByMessage = mapOf(messageId to setOf(partId)),
                streamingStateChangedIds = if (wasStreaming) emptySet() else setOf(messageId),
            )
        }

        private fun applyMessageRemoved(
            sessionId: String,
            messageId: String,
        ): SessionChangeSet? {
            val existed =
                infoCaches[sessionId]?.containsKey(messageId) == true ||
                    messageOrders[sessionId]?.contains(messageId) == true
            if (!existed) return null
            infoCaches[sessionId]?.remove(messageId)
            partsCaches[sessionId]?.remove(messageId)
            partOrders[sessionId]?.remove(messageId)
            ignoredPartIds[sessionId]?.remove(messageId)
            messageOrders[sessionId]?.removeAll { it == messageId }
            deltaChunks[sessionId]?.keys?.removeAll { it.startsWith("$messageId\u0000") }
            absorbedReplayChars[sessionId]?.keys?.removeAll { it.startsWith("$messageId\u0000") }
            return SessionChangeSet(
                sessionRevision = 0,
                removedMessageIds = setOf(messageId),
                structuralChange = true,
            )
        }

        private fun applyPartRemoved(
            sessionId: String,
            messageId: String,
            partId: String,
        ): SessionChangeSet? {
            val removed = partsCaches[sessionId]?.get(messageId)?.remove(partId) != null
            partOrders[sessionId]?.get(messageId)?.removeAll { it == partId }
            ignoredPartIds[sessionId]?.get(messageId)?.remove(partId)
            if (ignoredPartIds[sessionId]?.get(messageId)?.isEmpty() == true) {
                ignoredPartIds[sessionId]?.remove(messageId)
            }
            clearChunks(sessionId, messageId, partId)
            if (!removed) return null
            return SessionChangeSet(
                sessionRevision = 0,
                changedMessageIds = setOf(messageId),
                changedPartIdsByMessage = mapOf(messageId to setOf(partId)),
                structuralChange = true,
            )
        }

        private fun ensureCaches(sessionId: String) {
            infoCaches.getOrPut(sessionId) { mutableMapOf() }
            partsCaches.getOrPut(sessionId) { mutableMapOf() }
            partOrders.getOrPut(sessionId) { mutableMapOf() }
            ignoredPartIds.getOrPut(sessionId) { mutableMapOf() }
            messageOrders.getOrPut(sessionId) { mutableListOf() }
            deltaChunks.getOrPut(sessionId) { mutableMapOf() }
            absorbedReplayChars.getOrPut(sessionId) { mutableMapOf() }
        }

        private fun bumpRevision(sessionId: String) {
            revisions[sessionId] = (revisions[sessionId] ?: 0L) + 1L
        }

        private fun chunkKey(
            messageId: String,
            partId: String?,
        ): String? = partId?.let { "$messageId\u0000$it" }

        /**
         * Appends a delta chunk, absorbing late replay deltas already covered by a
         * snapshot base. Returns whether any new characters were stored.
         */
        private fun appendChunk(
            sessionId: String,
            messageId: String,
            partId: String,
            baseText: String,
            delta: String,
        ): Boolean {
            val key = chunkKey(messageId, partId) ?: return false
            ensureCaches(sessionId)
            val chunks = deltaChunks[sessionId]!!.getOrPut(key) { mutableListOf() }

            if (chunks.isEmpty() && baseText.isNotEmpty() && delta.isNotEmpty()) {
                val absorbedMap = absorbedReplayChars[sessionId]!!
                val absorbed = absorbedMap[key] ?: 0
                if (absorbed < baseText.length) {
                    val unmatched = baseText.substring(absorbed)
                    if (unmatched.startsWith(delta)) {
                        absorbedMap[key] = absorbed + delta.length
                        return false
                    }
                    if (delta.startsWith(unmatched)) {
                        absorbedMap[key] = baseText.length
                        val overflow = delta.substring(unmatched.length)
                        if (overflow.isEmpty()) return false
                        chunks.add(overflow)
                        return true
                    }
                    // Not a replay of the snapshot — stop absorbing and append.
                    absorbedMap[key] = baseText.length
                }
            }

            chunks.add(delta)
            return true
        }

        private fun clearChunks(
            sessionId: String,
            messageId: String,
            partId: String?,
        ) {
            val key = chunkKey(messageId, partId) ?: return
            deltaChunks[sessionId]?.remove(key)
            // Reset so delayed deltas that replay the new snapshot can be absorbed.
            absorbedReplayChars[sessionId]?.put(key, 0)
        }

        private fun hasChunks(
            sessionId: String,
            messageId: String,
            partId: String?,
        ): Boolean {
            val key = chunkKey(messageId, partId) ?: return false
            return deltaChunks[sessionId]?.get(key)?.isNotEmpty() == true
        }

        private fun flattenedText(
            sessionId: String,
            messageId: String,
            part: MessagePart.Text,
        ): String = flattenedText(sessionId, messageId, partId = part.id, baseText = part.text)

        private fun flattenedText(
            sessionId: String,
            messageId: String,
            partId: String?,
            baseText: String,
        ): String {
            val key = chunkKey(messageId, partId) ?: return baseText
            val chunks = deltaChunks[sessionId]?.get(key).orEmpty()
            if (chunks.isEmpty()) return baseText
            return buildString(baseText.length + chunks.sumOf { it.length }) {
                append(baseText)
                chunks.forEach { append(it) }
            }
        }

        private fun resolvePart(
            sessionId: String,
            messageId: String,
            partDto: MessagePartDto,
            existingPart: MessagePart?,
            delta: String?,
        ): MessagePart? {
            if (delta != null && partDto.type == "text") {
                val baseText =
                    when (existingPart) {
                        is MessagePart.Text -> existingPart.text
                        else -> partDto.text.orEmpty()
                    }
                val partId = partDto.id
                val textPart =
                    MessagePart.Text(
                        text = baseText,
                        isStreaming = true,
                        id = partId,
                        isSynthetic =
                            (existingPart as? MessagePart.Text)?.isSynthetic
                                ?: (partDto.synthetic == true),
                        isIgnored =
                            (existingPart as? MessagePart.Text)?.isIgnored
                                ?: (partDto.ignored == true),
                    )
                if (partId != null) {
                    appendChunk(sessionId, messageId, partId, baseText, delta)
                }
                return textPart
            }
            if (delta != null && partDto.type == "reasoning") {
                val baseText =
                    when (existingPart) {
                        is MessagePart.Reasoning -> existingPart.text
                        else -> partDto.text.orEmpty()
                    }
                val partId = partDto.id
                val existing = existingPart as? MessagePart.Reasoning
                val reasoningPart =
                    MessagePart.Reasoning(
                        text = baseText,
                        isStreaming = true,
                        id = partId,
                        timeStartEpochMs = existing?.timeStartEpochMs ?: partDto.time?.start,
                        timeEndEpochMs = existing?.timeEndEpochMs ?: partDto.time?.end,
                    )
                if (partId != null) {
                    appendChunk(sessionId, messageId, partId, baseText, delta)
                }
                return reasoningPart
            }
            return resolveReasoningTimes(resolveToolStart(adapter.toMessagePart(partDto), existingPart), existingPart)
        }

        private fun resolveReasoningTimes(
            part: MessagePart?,
            existing: MessagePart?,
        ): MessagePart? {
            val incoming = part as? MessagePart.Reasoning ?: return part
            val prior = existing as? MessagePart.Reasoning ?: return part
            val start = prior.timeStartEpochMs ?: incoming.timeStartEpochMs
            val end = incoming.timeEndEpochMs ?: prior.timeEndEpochMs
            if (start == incoming.timeStartEpochMs && end == incoming.timeEndEpochMs) return incoming
            return incoming.copy(timeStartEpochMs = start, timeEndEpochMs = end)
        }

        private fun resolveToolStart(
            part: MessagePart?,
            existing: MessagePart?,
        ): MessagePart? {
            val tool = part as? MessagePart.Tool ?: return part
            val existingStart = (existing as? MessagePart.Tool)?.timeStartEpochMs
            val resolved =
                ToolStartTime.resolve(
                    incoming = tool.timeStartEpochMs,
                    existing = existingStart,
                    status = tool.status,
                    nowEpochMs = System.currentTimeMillis(),
                )
            if (resolved == tool.timeStartEpochMs) return part
            return tool.copy(timeStartEpochMs = resolved)
        }

        private fun orderedParts(
            sessionId: String,
            messageId: String,
            parts: Map<String, Map<String, MessagePart>>,
            orders: Map<String, List<String>>,
        ): List<MessagePart> {
            val messageParts = parts[messageId] ?: return emptyList()
            val orderedIds = orders[messageId].orEmpty()
            val ordered =
                orderedIds.mapNotNull { partId ->
                    messageParts[partId]?.let { resolveStoredPart(sessionId, messageId, it) }
                }
            val leftovers =
                messageParts
                    .filterKeys { it !in orderedIds }
                    .toSortedMap()
                    .values
                    .map { resolveStoredPart(sessionId, messageId, it) }
            return ordered + leftovers
        }

        private fun resolveStoredPart(
            sessionId: String,
            messageId: String,
            part: MessagePart,
        ): MessagePart =
            when (part) {
                is MessagePart.Text -> {
                    val flattened = flattenedText(sessionId, messageId, part)
                    if (flattened == part.text) part else part.copy(text = flattened)
                }
                is MessagePart.Reasoning -> {
                    val flattened = flattenedText(sessionId, messageId, partId = part.id, baseText = part.text)
                    if (flattened == part.text) part else part.copy(text = flattened)
                }
                else -> part
            }

        private fun toolStartTimesLocked(sessionId: String): Map<String, Long> {
            val starts = mutableMapOf<String, Long>()
            partsCaches[sessionId]?.values?.forEach { parts ->
                parts.forEach { (partId, part) ->
                    val start = (part as? MessagePart.Tool)?.timeStartEpochMs
                    if (start != null) starts[partId] = start
                }
            }
            return starts
        }

        private fun prependOlderLocked(
            sessionId: String,
            older: List<Message>,
        ) {
            ensureCaches(sessionId)
            val order = messageOrders[sessionId]!!
            val existing = order.toHashSet()
            val prepended = older.filter { it.id !in existing }
            if (prepended.isEmpty()) return
            val previousStarts = toolStartTimesLocked(sessionId)
            messageOrders[sessionId] = (prepended.map { it.id } + order).toMutableList()
            val byId = baseMessages[sessionId].orEmpty().associateBy { it.id }.toMutableMap()
            for (message in prepended) {
                byId[message.id] = message
            }
            baseMessages[sessionId] = messageOrders[sessionId]!!.mapNotNull { byId[it] }
            seedPartIdentities(sessionId, prepended, previousStarts)
            bumpRevision(sessionId)
        }

        private fun mergeAuthoritativeLocked(
            sessionId: String,
            messages: List<Message>,
        ) {
            ensureCaches(sessionId)
            val previousStarts = toolStartTimesLocked(sessionId)
            val incomingIds = messages.map { it.id }.toSet()
            val existingParts = partsCaches[sessionId]!!
            val existingOrders = partOrders[sessionId]!!
            val now = System.currentTimeMillis()
            val nextParts = mutableMapOf<String, MutableMap<String, MessagePart>>()
            val nextOrders = mutableMapOf<String, MutableList<String>>()

            for (message in messages) {
                val restById = linkedMapOf<String, MessagePart>()
                for (part in message.parts) {
                    val partId = part.stablePartId() ?: continue
                    restById[partId] = resolveSeededTool(part, partId, previousStarts, now)
                }
                val prior = existingParts[message.id].orEmpty()
                if (restById.isEmpty()) {
                    if (prior.isEmpty()) continue
                    val finished = finishPartsAgainstRest(prior, message)
                    nextParts[message.id] = finished.toMutableMap()
                    nextOrders[message.id] =
                        (existingOrders[message.id] ?: finished.keys.toMutableList()).toMutableList()
                    continue
                }
                val merged = linkedMapOf<String, MessagePart>()
                val order = mutableListOf<String>()
                for ((partId, incoming) in restById) {
                    merged[partId] = mergePart(prior[partId], incoming)
                    order.add(partId)
                }
                nextParts[message.id] = merged
                nextOrders[message.id] = order
            }

            partsCaches[sessionId] = nextParts
            partOrders[sessionId] = nextOrders
            infoCaches[sessionId]!!.keys.retainAll(incomingIds)
            ignoredPartIds[sessionId]!!.keys.retainAll(incomingIds)
            messageOrders[sessionId] = messages.map { it.id }.toMutableList()
            baseMessages[sessionId] = messages
            deltaChunks[sessionId]!!.clear()
            absorbedReplayChars[sessionId]!!.clear()
            bumpRevision(sessionId)
        }

        private fun finishPartsAgainstRest(
            existing: Map<String, MessagePart>,
            rest: Message,
        ): Map<String, MessagePart> {
            val streamingTextIds =
                existing
                    .filter { (_, part) -> part is MessagePart.Text && part.isStreaming }
                    .keys
            val targetTextId =
                streamingTextIds.singleOrNull()
                    ?: existing.entries.lastOrNull { it.value is MessagePart.Text }?.key
            return existing.mapValues { (id, part) ->
                when (part) {
                    is MessagePart.Text -> {
                        val text =
                            if (id == targetTextId && rest.text.isNotBlank()) rest.text else part.text
                        val next = part.copy(text = text, isStreaming = false)
                        if (next == part) part else next
                    }
                    is MessagePart.Reasoning -> {
                        val next = part.copy(isStreaming = false)
                        if (next == part) part else next
                    }
                    else -> part
                }
            }
        }

        private fun mergePart(
            existing: MessagePart?,
            incoming: MessagePart,
        ): MessagePart {
            if (existing == null) {
                return if (incoming is MessagePart.Tool) {
                    incoming.copy(toolName = ToolPartIdentity.resolvedName(incoming.toolName, incoming.input))
                } else {
                    incoming
                }
            }
            val merged =
                when {
                    existing is MessagePart.Tool && incoming is MessagePart.Tool -> {
                        val mergedInput = mergeJson(existing.input, incoming.input)
                        existing.copy(
                            toolName =
                                ToolPartIdentity.resolvedName(
                                    ToolPartIdentity.coalesce(incoming.toolName, existing.toolName),
                                    mergedInput,
                                ),
                            status = incoming.status,
                            title = incoming.title?.takeIf { it.isNotBlank() } ?: existing.title,
                            error = incoming.error ?: existing.error,
                            callId = incoming.callId ?: existing.callId,
                            input = mergedInput,
                            output = incoming.output?.takeIf { it.isNotEmpty() } ?: existing.output,
                            metadata = mergeJson(existing.metadata, incoming.metadata),
                            timeStartEpochMs = incoming.timeStartEpochMs ?: existing.timeStartEpochMs,
                        )
                    }
                    existing is MessagePart.Text && incoming is MessagePart.Text -> {
                        val hasSnapshot = incoming.text.isNotBlank()
                        existing.copy(
                            text = if (hasSnapshot) incoming.text else existing.text,
                            isStreaming = if (hasSnapshot) incoming.isStreaming else existing.isStreaming,
                            id = incoming.id ?: existing.id,
                            isSynthetic = if (hasSnapshot) incoming.isSynthetic else existing.isSynthetic,
                            isIgnored = if (hasSnapshot) incoming.isIgnored else existing.isIgnored,
                        )
                    }
                    existing is MessagePart.Reasoning && incoming is MessagePart.Reasoning -> {
                        val hasSnapshot = incoming.text.isNotBlank()
                        existing.copy(
                            text = if (hasSnapshot) incoming.text else existing.text,
                            isStreaming =
                                when {
                                    hasSnapshot -> incoming.isStreaming
                                    incoming.timeEndEpochMs != null -> false
                                    else -> existing.isStreaming
                                },
                            id = incoming.id ?: existing.id,
                            timeStartEpochMs = incoming.timeStartEpochMs ?: existing.timeStartEpochMs,
                            timeEndEpochMs = incoming.timeEndEpochMs ?: existing.timeEndEpochMs,
                        )
                    }
                    existing is MessagePart.File && incoming is MessagePart.File ->
                        existing.copy(
                            filename = incoming.filename.ifBlank { existing.filename },
                            mime = incoming.mime.ifBlank { existing.mime },
                            kind = incoming.kind,
                            dataUrl = incoming.dataUrl.ifBlank { existing.dataUrl },
                        )
                    existing is MessagePart.Compaction && incoming is MessagePart.Compaction ->
                        existing.copy(
                            auto = existing.auto || incoming.auto,
                            completed = existing.completed || incoming.completed,
                        )
                    else -> incoming
                }
            return if (merged == existing) existing else merged
        }

        private fun mergeJson(
            existing: JsonObject?,
            incoming: JsonObject?,
        ): JsonObject? {
            if (incoming == null || incoming.isEmpty()) return existing
            if (existing == null || existing.isEmpty()) return incoming
            return JsonObject(existing + incoming)
        }

        /** Sparse overlays must not drop live text/reasoning deltas. */
        private fun replacesStreamSnapshot(incoming: MessagePart): Boolean =
            when (incoming) {
                is MessagePart.Text -> incoming.text.isNotBlank()
                is MessagePart.Reasoning -> incoming.text.isNotBlank()
                else -> true
            }

        private fun seedPartIdentities(
            sessionId: String,
            messages: List<Message>,
            previousStarts: Map<String, Long>,
        ) {
            val sessionParts = partsCaches[sessionId]!!
            val sessionOrders = partOrders[sessionId]!!
            val now = System.currentTimeMillis()
            for (message in messages) {
                val identifiedParts = mutableMapOf<String, MessagePart>()
                val identifiedOrder = mutableListOf<String>()
                for (part in message.parts) {
                    val partId = part.stablePartId() ?: continue
                    identifiedParts[partId] = resolveSeededTool(part, partId, previousStarts, now)
                    identifiedOrder.add(partId)
                }
                if (identifiedParts.isNotEmpty()) {
                    sessionParts[message.id] = identifiedParts
                    sessionOrders[message.id] = identifiedOrder
                }
            }
        }

        private fun resolveSeededTool(
            part: MessagePart,
            partId: String,
            previousStarts: Map<String, Long>,
            nowEpochMs: Long,
        ): MessagePart {
            val tool = part as? MessagePart.Tool ?: return part
            val resolved =
                ToolStartTime.resolve(
                    incoming = tool.timeStartEpochMs,
                    existing = previousStarts[partId],
                    status = tool.status,
                    nowEpochMs = nowEpochMs,
                )
            if (resolved == tool.timeStartEpochMs) return part
            return tool.copy(timeStartEpochMs = resolved)
        }

        private fun MessagePart.stablePartId(): String? =
            when (this) {
                is MessagePart.Text -> id
                is MessagePart.Tool -> id
                is MessagePart.File -> id
                is MessagePart.Compaction -> id
                is MessagePart.Reasoning -> id
            }

        private fun MessagePart.isStreamingPart(): Boolean =
            when (this) {
                is MessagePart.Text -> isStreaming
                is MessagePart.Reasoning -> isStreaming
                else -> false
            }

        private fun logReasoningOverlay(
            sessionId: String,
            messageId: String,
            partId: String,
            previous: MessagePart?,
            stored: MessagePart,
            inserted: Boolean,
        ) {
            val previousReasoning = previous as? MessagePart.Reasoning
            val storedReasoning = stored as? MessagePart.Reasoning ?: return
            val from = previousReasoning?.let(ThoughtPartChrome::paintLabel) ?: "none"
            val to = ThoughtPartChrome.paintLabel(storedReasoning)
            if (!inserted && from == to) return
            DiagnosticLog.info(
                DiagnosticLogArea.Chat,
                "reasoning overlay session=$sessionId message=$messageId part=$partId " +
                    "from=$from to=$to inserted=${if (inserted) 1 else 0} " +
                    "chars=${storedReasoning.text.length} " +
                    "streaming=${if (storedReasoning.isStreaming) 1 else 0} " +
                    "end=${if (storedReasoning.timeEndEpochMs != null) 1 else 0}",
            )
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

        companion object {
        }
    }
