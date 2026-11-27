package dev.opencode.mobile.domain.session

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.json.serverPayloadJson
import dev.opencode.mobile.domain.message.MessageError
import dev.opencode.mobile.domain.text.UiText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Why a server request failed, read from its HTTP error body. The server's own words stay
 * verbatim ([Server]); the cases the app explains in its own words are typed, so the app maps
 * them to keyed text ([text]) instead of carrying English.
 */
sealed interface ServerErrorReason {
    /** `UnauthorizedError`: the pairing is no longer valid. */
    data object PairingExpired : ServerErrorReason

    /** `LocationNotFoundError` / `FileNotFoundError`; [path] is the server's path, when it sent one. */
    data class NotFound(
        val path: String?,
    ) : ServerErrorReason

    /** `SessionBusyError` / `BusyError`: the session is running a turn. */
    data object SessionBusy : ServerErrorReason

    /** The server's own message, with its error [ref] when it sent one. */
    data class Server(
        val message: String,
        val ref: String? = null,
    ) : ServerErrorReason

    /** The server's words when the reason is its own; null when the app explains it. */
    val serverText: String?
        get() = (this as? Server)?.let { if (it.ref.isNullOrBlank()) it.message else "${it.message} (${it.ref})" }

    /** What the app shows. */
    val text: UiText
        get() =
            when (this) {
                PairingExpired -> UiText.resource(R.string.server_error_pairing_expired)
                is NotFound ->
                    if (path.isNullOrBlank()) {
                        UiText.resource(R.string.server_error_not_found)
                    } else {
                        UiText.resource(R.string.server_error_not_found_path, path)
                    }
                SessionBusy -> UiText.resource(R.string.server_error_session_busy)
                is Server ->
                    if (ref.isNullOrBlank()) {
                        UiText.verbatim(message)
                    } else {
                        UiText.resource(R.string.server_error_with_ref, message, ref)
                    }
            }
}

/**
 * The server's own error from an HTTP error body, when it is worth showing a user (e.g.
 * `{"_tag":"FormInvalidAnswerError","message":"Too many selections …"}`). Request-schema
 * failures (`InvalidRequestError`, multi-line decoder paths) are client bugs, not something
 * the user can act on, so they return null.
 */
object ServerErrorMessage {
    private const val MAX_LENGTH = 200
    private val json = serverPayloadJson()

    /** The reason a [SessionError.Http] or [MessageError.Http] carries, if any. */
    fun from(error: Throwable): ServerErrorReason? =
        when (error) {
            is SessionError.Http -> error.body?.let(::reason)
            is MessageError.Http -> error.reason
            else -> null
        }

    /** [from] as the text the app shows, or null. */
    fun text(error: Throwable): UiText? = from(error)?.text

    /**
     * The reason for a non-2xx JSON body. Known tags are typed. Schema failures stay hidden.
     * Other tags use [fromBody].
     */
    fun reason(body: String?): ServerErrorReason? {
        val trimmed = body?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val tag = root.string("_tag") ?: root.string("name")
        val data = root["data"] as? JsonObject
        return when (tag) {
            "InvalidRequestError" -> null
            "UnauthorizedError" -> ServerErrorReason.PairingExpired
            "LocationNotFoundError", "FileNotFoundError" ->
                ServerErrorReason.NotFound(
                    root.string("path") ?: root.string("location") ?: data?.string("path") ?: data?.string("location"),
                )
            "SessionBusyError", "BusyError" -> ServerErrorReason.SessionBusy
            "UnknownError" -> {
                val ref = (root.string("ref") ?: data?.string("ref"))?.takeIf { it.isNotBlank() }
                val message = fromBody(trimmed)
                when {
                    message != null -> ServerErrorReason.Server(message, ref)
                    ref != null -> ServerErrorReason.Server(ref)
                    else -> null
                }
            }
            else -> fromBody(trimmed)?.let { ServerErrorReason.Server(it) }
        }
    }

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
