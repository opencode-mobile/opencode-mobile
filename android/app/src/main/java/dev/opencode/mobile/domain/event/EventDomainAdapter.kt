package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.dto.EventEnvelopeDto
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EventDomainAdapter(
    private val json: Json,
) {
    fun parseEvent(dataLine: String): OpencodeEvent? {
        val envelope = runCatching { json.decodeFromString<EventEnvelopeDto>(dataLine) }.getOrNull() ?: return null
        val payload = envelope.payload
        val type = payload?.type ?: envelope.type ?: return null
        val properties = payload?.properties ?: envelope.properties ?: JsonObject(emptyMap())
        return OpencodeEvent(
            type = type,
            sessionId = sessionIdFromProperties(properties),
            rawProperties = properties,
        )
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

    fun statusLabel(sessionStatus: SessionStatus): String? = statusLabelFromRawKeys(sessionStatus.raw)

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

    private fun statusLabelFromRaw(status: JsonObject): String? {
        val keys = status.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
        return statusLabelFromRawKeys(keys)
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
