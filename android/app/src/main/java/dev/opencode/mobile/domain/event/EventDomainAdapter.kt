package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.message.AttachmentDataUrl
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EventDomainAdapter(
    private val json: Json,
) {
    fun parseEvent(dataLine: String): OpencodeEvent? = parseEvents(dataLine).firstOrNull()

    fun parseEvents(dataLine: String): List<OpencodeEvent> {
        val raw = runCatching { json.parseToJsonElement(dataLine) }.getOrNull() as? JsonObject ?: return emptyList()
        val payload = raw["payload"] as? JsonObject
        val dataProperties = propertiesFromData(raw["data"])
        val type =
            stringIn(payload, "type")
                ?: stringIn(raw, "type")
                ?: stringIn(raw, "event")
                ?: stringIn(dataProperties, "type")
                ?: return emptyList()
        val properties = withEnvelopeCreated(resolveProperties(payload, raw, dataProperties), raw)
        if (canonicalEventType(type) == "sync") {
            val syncEvent =
                (payload?.get("syncEvent") as? JsonObject)
                    ?: properties["syncEvent"] as? JsonObject
                    ?: return emptyList()
            val innerType = stringIn(syncEvent, "type") ?: return emptyList()
            val data = propertiesFromData(syncEvent["data"]) ?: JsonObject(emptyMap())
            return stampEnvelopeId(
                project(canonicalEventType(innerType), data),
                stringIn(syncEvent, "id") ?: stringIn(payload, "id") ?: stringIn(raw, "id"),
            )
        }
        return stampEnvelopeId(
            project(canonicalEventType(type), properties),
            stringIn(payload, "id") ?: stringIn(raw, "id"),
        )
    }

    private fun stampEnvelopeId(
        events: List<OpencodeEvent>,
        id: String?,
    ): List<OpencodeEvent> {
        if (id.isNullOrBlank()) return events
        return events.map { event -> if (event.id == null) event.copy(id = id) else event }
    }

    private fun propertiesFromData(data: JsonElement?): JsonObject? =
        when (data) {
            is JsonObject -> data
            is JsonPrimitive ->
                data.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                }
            else -> null
        }

    private fun resolveProperties(
        payload: JsonObject?,
        raw: JsonObject,
        dataProperties: JsonObject?,
    ): JsonObject {
        val candidates =
            listOfNotNull(
                payload?.get("properties") as? JsonObject,
                raw["properties"] as? JsonObject,
                nestedEventProperties(dataProperties),
                flattenedEventFields(payload),
                flattenedEventFields(raw),
            )
        return candidates.firstOrNull { it.containsKey("prompt") }
            ?: candidates.firstOrNull { it.containsKey("sessionID") || it.containsKey("messageID") }
            ?: candidates.firstOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun withEnvelopeCreated(
        properties: JsonObject,
        raw: JsonObject,
    ): JsonObject {
        if (properties.containsKey("created")) return properties
        val created = raw["created"] ?: return properties
        return JsonObject(properties + ("created" to created))
    }

    private fun flattenedEventFields(source: JsonObject?): JsonObject? {
        if (source == null) return null
        val fields =
            source.filterKeys { key ->
                key !in WRAPPER_KEYS
            }
        return fields.takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
    }

    private fun nestedEventProperties(data: JsonObject?): JsonObject? {
        if (data == null) return null
        if (data.containsKey("prompt")) return data
        data["properties"]?.jsonObject?.let { return it }
        data["data"]?.let { nested -> propertiesFromData(nested) }?.let { return it }
        return data
    }

    private fun canonicalEventType(type: String): String = type.replace(Regex("""\.\d+$"""), "")

    private fun project(
        type: String,
        properties: JsonObject,
    ): List<OpencodeEvent> =
        when (type) {
            "permission.v2.asked" -> listOf(event("permission.asked", properties))
            "permission.v2.replied" -> listOf(event("permission.replied", properties))
            "question.v2.asked",
            "form.created",
            -> listOf(event("question.asked", properties))
            "question.v2.replied",
            "form.replied",
            -> listOf(event("question.replied", properties))
            "question.v2.rejected",
            "form.cancelled",
            -> listOf(event("question.rejected", properties))
            "session.next.prompt.admitted",
            "session.next.prompted",
            "session.inbox.enqueued",
            "session.inbox.delivered",
            "session.inbox.delivery.changed",
            -> userPromptEvents(properties)
            "session.inbox.cancelled" -> listOf(inboxCancelled(properties))
            "session.next.step.started",
            "session.step.started",
            -> stepStarted(properties)
            "session.execution.started" -> listOf(statusEvent(properties, "busy"))
            "session.next.step.ended",
            "session.step.ended",
            ->
                listOfNotNull(
                    assistantMessageUpdated(properties),
                    event("session.active.snapshot", properties),
                )
            "session.execution.succeeded",
            "session.execution.interrupted",
            -> listOf(event("session.idle", properties))
            "session.next.step.failed",
            "session.execution.failed",
            ->
                listOf(
                    event("session.error", properties),
                    event("session.idle", properties),
                )
            "session.step.failed" ->
                listOfNotNull(
                    event("session.error", properties),
                    assistantMessageUpdated(properties),
                )
            "session.next.retried",
            "session.retry.scheduled",
            -> listOf(statusEvent(properties, "retry"))
            "session.next.tool.input.started",
            "session.tool.input.started",
            "session.next.tool.input.ended",
            "session.tool.input.ended",
            "session.next.tool.called",
            "session.tool.called",
            "session.next.tool.progress",
            "session.tool.progress",
            "session.next.tool.success",
            "session.tool.success",
            "session.next.tool.failed",
            "session.tool.failed",
            -> listOf(toolPartUpdated(type, properties))
            "session.next.tool.input.delta",
            "session.tool.input.delta",
            -> listOf(toolInputDelta(properties))
            "session.next.shell.started",
            "session.shell.started",
            ->
                listOf(
                    statusEvent(properties, "busy"),
                    shellPartUpdated(properties, status = "running"),
                )
            "session.next.shell.ended",
            "session.shell.ended",
            ->
                listOf(
                    shellPartUpdated(properties, status = "completed"),
                    event("session.active.snapshot", properties),
                )
            "session.next.compaction.started",
            "session.compaction.started",
            ->
                listOf(
                    statusEvent(properties, "busy"),
                    compactionPartUpdated(properties, completed = false),
                )
            "session.next.compaction.delta",
            "session.compaction.delta",
            -> listOf(compactionTextDelta(properties))
            "session.next.compaction.ended",
            "session.compaction.ended",
            ->
                listOf(
                    compactionPartUpdated(properties, completed = true),
                    event("session.active.snapshot", properties),
                )
            "session.next.compaction.failed",
            "session.compaction.failed",
            ->
                listOf(
                    compactionPartUpdated(properties, completed = true),
                    event("session.error", properties),
                    event("session.idle", properties),
                )
            "session.next.reasoning.started",
            "session.reasoning.started",
            -> reasoningStarted(properties)
            "session.next.reasoning.delta",
            "session.reasoning.delta",
            -> listOf(reasoningDelta(properties))
            "session.next.reasoning.ended",
            "session.reasoning.ended",
            -> listOf(reasoningPartUpdated(properties, completed = true))
            "session.next.text.started",
            "session.text.started",
            -> textStarted(properties)
            "session.next.text.delta",
            "session.text.delta",
            -> listOf(textDelta(properties))
            "session.next.text.ended",
            "session.text.ended",
            -> listOf(textPartUpdated(properties))
            "session.next.moved",
            "session.next.agent.switched",
            "session.next.model.switched",
            "session.moved",
            "session.agent.selected",
            "session.model.selected",
            -> listOf(event("session.updated", properties))
            "session.renamed" -> listOf(renamedToUpdated(properties))
            "session.usage.updated",
            "session.next.usage.updated",
            -> listOfNotNull(usageUpdated(properties))
            else -> listOf(event(type, properties))
        }

    private fun event(
        type: String,
        properties: JsonObject,
    ): OpencodeEvent =
        OpencodeEvent(
            type = type,
            sessionId = sessionIdFromProperties(properties),
            rawProperties = properties,
        )

    private fun usageUpdated(properties: JsonObject): OpencodeEvent? {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID") ?: properties.string("messageID") ?: return null
        val tokens = properties["tokens"]
        val info =
            buildJsonObject {
                put("id", assistantId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
                if (tokens != null) put("tokens", tokens)
                properties["cost"]?.let { put("cost", it) }
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun renamedToUpdated(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val info =
            buildJsonObject {
                sessionId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("title")?.let { put("title", it) }
            }
        return event("session.updated", buildJsonObject { put("info", info) })
    }

    private fun inboxCancelled(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
            }
        return event("message.removed", projected)
    }

    private fun userPromptEvents(properties: JsonObject): List<OpencodeEvent> =
        listOf(userMessageUpdated(properties)) + userPromptParts(properties)

    private fun userMessageUpdated(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val info =
            buildJsonObject {
                messageId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                put("role", "user")
                properties.long("created")?.let { created ->
                    put("time", buildJsonObject { put("created", created) })
                }
                (properties.string("agent") ?: properties.string("mode"))?.let { agent ->
                    put("agent", agent)
                    put("mode", agent)
                }
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun userPromptParts(properties: JsonObject): List<OpencodeEvent> {
        val prompt = promptObject(properties)
        val sessionId = sessionIdFromProperties(properties)
        val messageId = userMessageId(properties)
        val parts = mutableListOf<OpencodeEvent>()
        val text = prompt?.string("text") ?: properties.string("text")
        text?.takeIf { it.isNotBlank() }?.let { parts += userTextPartUpdated(sessionId, messageId, it) }
        if (prompt == null) return parts
        val files = prompt["files"] as? JsonArray ?: return parts
        files.forEachIndexed { index, element ->
            val file = element as? JsonObject ?: return@forEachIndexed
            parts += userFilePartUpdated(sessionId, messageId, index, file)
        }
        return parts
    }

    private fun userTextPartUpdated(
        sessionId: String?,
        messageId: String?,
        text: String,
    ): OpencodeEvent {
        val part =
            buildJsonObject {
                messageId?.let { put("id", "$it:text:0") }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "text")
                put("text", text)
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun userFilePartUpdated(
        sessionId: String?,
        messageId: String?,
        index: Int,
        file: JsonObject,
    ): OpencodeEvent {
        val part =
            buildJsonObject {
                messageId?.let { put("id", "$it:file:$index") }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "file")
                file.string("uri")?.let { put("url", it) }
                val mime =
                    file.string("mime")
                        ?: file.string("uri")?.let(AttachmentDataUrl::mimeFromDataUrl)
                        ?: file.string("name")?.let(AttachmentDataUrl::mimeFromFilename)
                mime?.let { put("mime", it) }
                file.string("name")?.let { put("filename", it) }
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun promptObject(properties: JsonObject): JsonObject? =
        decodePromptValue(properties["prompt"])
            ?: (properties["item"] as? JsonObject)?.let { decodePromptValue(it["payload"]) }

    private fun decodePromptValue(value: JsonElement?): JsonObject? =
        when (value) {
            is JsonObject -> value
            is JsonPrimitive ->
                value.contentOrNull?.let { raw ->
                    runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                }
            else -> null
        }

    private fun userMessageId(properties: JsonObject): String? =
        properties.string("messageID")
            ?: properties.string("id")
            ?: properties.string("inboxID")

    private fun stepStarted(properties: JsonObject): List<OpencodeEvent> =
        listOfNotNull(
            assistantMessageUpdated(properties),
            statusEvent(properties, "busy"),
        )

    private fun assistantMessageUpdated(properties: JsonObject): OpencodeEvent? {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID") ?: properties.string("messageID") ?: return null
        val info =
            buildJsonObject {
                put("id", assistantId)
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun toolPartUpdated(
        type: String,
        properties: JsonObject,
    ): OpencodeEvent {
        val status =
            when {
                type.endsWith("tool.input.started") || type.endsWith("tool.input.ended") -> "pending"
                type.endsWith("tool.success") -> "completed"
                type.endsWith("tool.failed") -> "error"
                else -> "running"
            }
        val name = properties.string("tool") ?: properties.string("name") ?: "tool"
        val callId = properties.string("callID")
        val partId = properties.string("id") ?: callId
        val error =
            properties["error"]
                ?.jsonObject
                ?.string("message")
        val output =
            properties.string("output")
                ?: properties["content"]
                    ?.let { content ->
                        if (content is kotlinx.serialization.json.JsonArray) {
                            content.mapNotNull { item ->
                                item.jsonObject.string("text")
                            }.joinToString("")
                        } else {
                            null
                        }
                    }?.takeIf { it.isNotEmpty() }
        val input = properties["input"]
        val metadata = properties["structured"]?.jsonObject
        return toolLikePartUpdated(
            properties = properties,
            messageId = properties.string("assistantMessageID"),
            partId = partId,
            toolName = name,
            status = status,
            callId = callId,
            input = input,
            output = output,
            error = error,
            metadata = metadata,
        )
    }

    private fun toolInputDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                (properties.string("id") ?: properties.string("callID"))?.let { put("partID", it) }
                put("field", "input")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun shellPartUpdated(
        properties: JsonObject,
        status: String,
    ): OpencodeEvent =
        toolLikePartUpdated(
            properties = properties,
            messageId = properties.string("messageID") ?: properties.string("assistantMessageID"),
            partId = properties.string("callID"),
            toolName = "bash",
            status = status,
            callId = properties.string("callID"),
            output = properties.string("output"),
            title = "Shell",
        )

    private fun compactionPartUpdated(
        properties: JsonObject,
        completed: Boolean,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = properties.string("messageID")
        val part =
            buildJsonObject {
                messageId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "compaction")
                put("auto", properties.string("reason") == "auto")
                put("completed", completed)
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun compactionTextDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = properties.string("messageID")
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                messageId?.let { put("partID", "$it:text:0") }
                put("field", "text")
                properties.string("text")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun toolLikePartUpdated(
        properties: JsonObject,
        messageId: String?,
        partId: String?,
        toolName: String,
        status: String,
        callId: String?,
        input: JsonElement? = null,
        output: String? = null,
        error: String? = null,
        metadata: JsonObject? = null,
        title: String? = null,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val state =
            buildJsonObject {
                put("status", status)
                title?.let { put("title", it) }
                if (input is JsonObject) put("input", input)
                output?.let { put("output", it) }
                error?.let { put("error", it) }
                metadata?.let { put("metadata", it) }
                properties.long("created")?.let { created ->
                    put("time", buildJsonObject { put("start", created) })
                }
            }
        val part =
            buildJsonObject {
                partId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                messageId?.let { put("messageID", it) }
                put("type", "tool")
                put("tool", toolName)
                callId?.let { put("callID", it) }
                put("state", state)
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun reasoningStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            assistantId?.let {
                buildJsonObject {
                    put("id", it)
                    sessionId?.let { id -> put("sessionID", id) }
                    put("role", "assistant")
                }
            }
        return listOfNotNull(
            info?.let { event("message.updated", buildJsonObject { put("info", it) }) },
            reasoningPartUpdated(properties, text = ""),
        )
    }

    private fun reasoningDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                properties.reasoningPartId()?.let { put("partID", it) }
                put("field", "reasoning")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun reasoningPartUpdated(
        properties: JsonObject,
        text: String? = properties.string("text"),
        completed: Boolean = false,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val part =
            buildJsonObject {
                properties.reasoningPartId()?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                put("type", "reasoning")
                put("text", text.orEmpty())
                properties.long("created")?.let { created ->
                    put(
                        "time",
                        buildJsonObject {
                            put("start", created)
                            if (completed) put("end", created)
                        },
                    )
                }
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun textStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            assistantId?.let {
                buildJsonObject {
                    put("id", it)
                    sessionId?.let { sid -> put("sessionID", sid) }
                    put("role", "assistant")
                }
            }
        return listOfNotNull(
            info?.let { event("message.updated", buildJsonObject { put("info", it) }) },
            textPartUpdated(properties, text = ""),
        )
    }

    private fun textDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                properties.textPartId()?.let { put("partID", it) }
                put("field", "text")
                properties.string("delta")?.let { put("delta", it) }
            }
        return event("message.part.delta", projected)
    }

    private fun textPartUpdated(
        properties: JsonObject,
        text: String? = properties.string("text"),
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val part =
            buildJsonObject {
                properties.textPartId()?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                put("type", "text")
                put("text", text.orEmpty())
            }
        return event("message.part.updated", buildJsonObject { put("part", part) })
    }

    private fun statusEvent(
        properties: JsonObject,
        statusType: String,
    ): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                put("status", buildJsonObject { put("type", statusType) })
            }
        return event("session.status", projected)
    }

    private fun sessionIdFromProperties(properties: JsonObject): String? {
        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        return properties.string("sessionID")
            ?: properties.string("sessionId")
            ?: properties["info"]?.jsonObject?.string("sessionID")
            ?: properties["info"]?.jsonObject?.string("sessionId")
            ?: properties["part"]?.jsonObject?.string("sessionID")
            ?: properties["part"]?.jsonObject?.string("sessionId")
            ?: properties["form"]?.jsonObject?.string("sessionID")
            ?: properties["form"]?.jsonObject?.string("sessionId")
    }

    private fun JsonObject.textPartId(): String? {
        string("textID")?.let { return it }
        val ordinal = this["ordinal"]?.jsonPrimitive ?: return null
        val value = ordinal.intOrNull?.toString() ?: ordinal.contentOrNull ?: return null
        return "text-$value"
    }

    private fun JsonObject.reasoningPartId(): String? {
        string("reasoningID")?.let { return it }
        string("id")?.let { return it }
        val ordinal = this["ordinal"]?.jsonPrimitive ?: return null
        val value = ordinal.intOrNull?.toString() ?: ordinal.contentOrNull ?: return null
        return "reasoning-$value"
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun stringIn(
        obj: JsonObject?,
        key: String,
    ): String? = obj?.string(key)

    fun statusLabel(sessionStatus: SessionStatus): String? = statusLabelFromRawKeys(sessionStatus.raw)

    /**
     * Status carried by a `session.status` SSE payload.
     * Returns null when the event is not a usable status update.
     */
    fun sessionStatusFromEvent(event: OpencodeEvent): SessionStatus? {
        if (event.type != "session.status") return null
        val sessionId = event.sessionId ?: return null
        val status = event.rawProperties?.get("status")?.jsonObject ?: return null
        val raw =
            status.mapValues { (_, value) ->
                val primitive = value as? JsonPrimitive
                primitive?.contentOrNull ?: primitive?.content ?: value.toString()
            }
        return SessionStatus(sessionId = sessionId, raw = raw)
    }

    /**
     * Title change carried by a `session.updated` event for a concrete session id.
     * Returns null when the event is not a usable title update.
     */
    fun sessionTitleUpdate(event: OpencodeEvent): SessionTitleUpdate? {
        if (event.type != "session.updated") return null
        val info = event.rawProperties?.get("info")?.jsonObject ?: return null
        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        val sessionId =
            event.sessionId
                ?: info.string("id")
                ?: info.string("sessionID")
                ?: info.string("sessionId")
                ?: return null
        val title = info.string("title") ?: return null
        return SessionTitleUpdate(sessionId = sessionId, title = title)
    }

    private fun statusLabelFromRawKeys(raw: Map<String, String>): String? =
        when (raw["type"]) {
            "busy" -> "Working"
            "retry" -> "Retrying"
            else -> null
        }

    companion object {
        private val WRAPPER_KEYS =
            setOf(
                "payload",
                "type",
                "event",
                "id",
                "durable",
                "location",
                "metadata",
                "properties",
                "data",
                "directory",
                "project",
                "workspace",
            )
    }
}

data class SessionTitleUpdate(
    val sessionId: String,
    val title: String,
)
