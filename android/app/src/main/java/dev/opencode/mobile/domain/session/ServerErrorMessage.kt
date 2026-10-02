package dev.opencode.mobile.domain.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The server's own error text from an HTTP error body, when it is worth showing a
 * user (e.g. `{"_tag":"FormInvalidAnswerError","message":"Too many selections …"}`).
 * Request-schema failures (`InvalidRequestError`, multi-line decoder paths) are
 * client bugs, not something the user can act on, so they return null.
 */
object ServerErrorMessage {
    private const val MAX_LENGTH = 200
    private val json = Json { ignoreUnknownKeys = true }

    fun from(error: Throwable): String? = (error as? SessionError.Http)?.body?.let(::fromBody)

    fun fromBody(body: String?): String? {
        val trimmed = body?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val tag = root.string("_tag") ?: root.string("name")
        if (tag == "InvalidRequestError" || root.string("kind") == "Payload") return null
        val message =
            root.string("message")
                ?: (root["data"] as? JsonObject)?.string("message")
                ?: (root["error"] as? JsonObject)?.string("message")
        val text = message?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        if (text.contains('\n') || text.length > MAX_LENGTH) return null
        return text
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
