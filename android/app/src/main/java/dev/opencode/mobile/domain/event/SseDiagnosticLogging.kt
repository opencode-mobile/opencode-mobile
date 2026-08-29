package dev.opencode.mobile.domain.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object SseDiagnosticLogging {
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
            "session.active.snapshot",
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
}

private fun JsonObject?.statusType(): String? {
    val status = this?.get("status") as? JsonObject ?: return null
    return status["type"]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject?.errorMessage(): String? {
    val error = this?.get("error") as? JsonObject
    val data = error?.get("data") as? JsonObject
    return listOf(
        data?.get("message")?.jsonPrimitive?.contentOrNull,
        error?.get("message")?.jsonPrimitive?.contentOrNull,
        this?.get("message")?.jsonPrimitive?.contentOrNull,
        error?.get("name")?.jsonPrimitive?.contentOrNull,
        error?.get("type")?.jsonPrimitive?.contentOrNull,
    ).firstOrNull { !it.isNullOrEmpty() }
}
