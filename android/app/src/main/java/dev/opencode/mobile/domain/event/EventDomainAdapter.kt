package dev.opencode.mobile.domain.event

import dev.opencode.mobile.data.remote.dto.EventEnvelopeDto
import dev.opencode.mobile.domain.session.SessionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class EventDomainAdapter(
    private val json: Json,
) {
    fun parseEvent(dataLine: String): OpencodeEvent? {
        val envelope = runCatching { json.decodeFromString<EventEnvelopeDto>(dataLine) }.getOrNull() ?: return null
        val payload = envelope.payload
        val type = payload?.type ?: envelope.type ?: return null
        val properties = payload?.properties ?: envelope.properties ?: JsonObject(emptyMap())
        val sessionId =
            properties["sessionID"]?.jsonPrimitive?.contentOrNull
                ?: properties["sessionId"]?.jsonPrimitive?.contentOrNull
        return OpencodeEvent(type = type, sessionId = sessionId)
    }

    fun statusLabel(sessionStatus: SessionStatus): String? = statusLabelFromRawKeys(sessionStatus.raw)

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
