package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object SseDiagnosticLogging {
    fun parseFailedMessage(dataLine: String): String {
        val trimmed = dataLine.trim()
        val type = EVENT_TYPE.find(trimmed)?.groupValues?.getOrNull(1) ?: "-"
        return "SSE event parse failed type=$type chars=${trimmed.length}"
    }

    fun bufferMessage(event: OpencodeEvent): String? =
        when (event.type) {
            "server.heartbeat",
            "message.part.delta",
            "message.part.updated",
            "message.updated",
            "file.watcher.updated",
            "file.edited",
            "session.diff",
            "session.updated",
            -> null
            "session.status" -> {
                val status = event.rawProperties.statusType() ?: "-"
                "SSE event type=session.status session=${event.sessionId ?: "-"} status=$status"
            }
            "session.error" -> {
                val error = event.rawProperties.errorMessage() ?: "-"
                "SSE event type=session.error session=${event.sessionId ?: "-"} error=$error"
            }
            else -> "SSE event type=${event.type} session=${event.sessionId ?: "-"}"
        }

    private val EVENT_TYPE = Regex(""""type"\s*:\s*"([^"]+)"""")
}

private fun JsonObject?.statusType(): String? {
    val status = this?.get("status") as? JsonObject ?: return null
    return (status["type"] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject?.errorMessage(): String? {
    val error = this?.get("error") as? JsonObject
    val data = error?.get("data") as? JsonObject
    return sequenceOf(
        (data?.get("message") as? JsonPrimitive)?.contentOrNull,
        (error?.get("message") as? JsonPrimitive)?.contentOrNull,
        (this?.get("message") as? JsonPrimitive)?.contentOrNull,
        (error?.get("name") as? JsonPrimitive)?.contentOrNull,
        (error?.get("type") as? JsonPrimitive)?.contentOrNull,
    ).firstOrNull { !it.isNullOrEmpty() }
}
