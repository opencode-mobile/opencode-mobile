package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.dto.EventEnvelopeDto
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class EventDomainAdapter(
    private val json: Json,
) {
    fun parseEvent(dataLine: String): OpencodeEvent? = parseEvents(dataLine).firstOrNull()

    fun parseEvents(dataLine: String): List<OpencodeEvent> {
        val envelope = runCatching { json.decodeFromString<EventEnvelopeDto>(dataLine) }.getOrNull() ?: return emptyList()
        val payload = envelope.payload
        val type = payload?.type ?: envelope.type ?: return emptyList()
        val properties =
            payload?.properties
                ?: envelope.properties
                ?: envelope.data
                ?: JsonObject(emptyMap())
        return project(type, properties)
    }

    private fun project(
        type: String,
        properties: JsonObject,
    ): List<OpencodeEvent> =
        when (type) {
            "permission.v2.asked" -> listOf(event("permission.asked", properties))
            "permission.v2.replied" -> listOf(event("permission.replied", properties))
            "question.v2.asked" -> listOf(event("question.asked", properties))
            "question.v2.replied" -> listOf(event("question.replied", properties))
            "question.v2.rejected" -> listOf(event("question.rejected", properties))
            "session.next.prompt.admitted",
            "session.next.prompted",
            -> listOf(userMessageUpdated(properties))
            "session.next.step.started" -> stepStarted(properties)
            "session.next.step.ended" -> listOf(event("session.idle", properties))
            "session.next.step.failed" ->
                listOf(
                    event("session.error", properties),
                    event("session.idle", properties),
                )
            "session.next.retried" -> listOf(statusEvent(properties, "retry"))
            "session.next.text.started" -> textStarted(properties)
            "session.next.text.delta" -> listOf(textDelta(properties))
            "session.next.text.ended" -> listOf(textPartUpdated(properties))
            "session.next.moved",
            "session.next.agent.switched",
            "session.next.model.switched",
            -> listOf(event("session.updated", properties))
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

    private fun userMessageUpdated(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val messageId = properties.string("messageID") ?: properties.string("id")
        val info =
            buildJsonObject {
                messageId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                put("role", "user")
            }
        return event("message.updated", buildJsonObject { put("info", info) })
    }

    private fun stepStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            buildJsonObject {
                assistantId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
            }
        return listOf(
            event("message.updated", buildJsonObject { put("info", info) }),
            statusEvent(properties, "busy"),
        )
    }

    private fun textStarted(properties: JsonObject): List<OpencodeEvent> {
        val sessionId = sessionIdFromProperties(properties)
        val assistantId = properties.string("assistantMessageID")
        val info =
            buildJsonObject {
                assistantId?.let { put("id", it) }
                sessionId?.let { put("sessionID", it) }
                put("role", "assistant")
            }
        return listOf(
            event("message.updated", buildJsonObject { put("info", info) }),
            textPartUpdated(properties, text = ""),
        )
    }

    private fun textDelta(properties: JsonObject): OpencodeEvent {
        val sessionId = sessionIdFromProperties(properties)
        val projected =
            buildJsonObject {
                sessionId?.let { put("sessionID", it) }
                properties.string("assistantMessageID")?.let { put("messageID", it) }
                properties.string("textID")?.let { put("partID", it) }
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
                properties.string("textID")?.let { put("id", it) }
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
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

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
}

data class SessionTitleUpdate(
    val sessionId: String,
    val title: String,
)
