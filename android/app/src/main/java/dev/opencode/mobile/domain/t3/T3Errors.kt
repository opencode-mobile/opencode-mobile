package dev.opencode.mobile.domain.t3

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Non-2xx reply from a T3 host. [message] is user-readable and never echoes the
 * server body; [body] stays available for diagnostics only.
 */
open class T3HttpException(
    val statusCode: Int,
    val body: String,
    message: String = messageFor(statusCode),
) : Exception(message) {
    companion object {
        fun messageFor(statusCode: Int): String = "T3 request failed ($statusCode)"

        /** Maps 401/403 to [T3AccessRevokedException]; every other status stays a plain [T3HttpException]. */
        fun from(
            statusCode: Int,
            body: String,
        ): T3HttpException =
            if (statusCode == 401 || statusCode == 403) {
                T3AccessRevokedException(statusCode, body)
            } else {
                T3HttpException(statusCode, body)
            }
    }
}

/** The stored access token was rejected (401/403). The user must pair again. */
class T3AccessRevokedException(
    statusCode: Int,
    body: String,
) : T3HttpException(statusCode, body, USER_MESSAGE) {
    companion object {
        const val USER_MESSAGE = "T3 access was revoked or expired. Re-pair this environment."
    }
}

/** The environment speaks a protocol this app cannot use; reconnecting will not help. */
class T3ProtocolException(
    message: String,
) : Exception(message)

/** The host could not be reached at all (DNS, TCP, TLS, timeout). The cause keeps the details. */
class T3TransportException(
    cause: Throwable,
) : Exception(USER_MESSAGE, cause) {
    companion object {
        const val USER_MESSAGE = "Can't reach the T3 environment"
    }
}

/** A reply from the host did not parse. [detail] is for diagnostics only. */
class T3DecodingException(
    val detail: String?,
    cause: Throwable? = null,
) : Exception(USER_MESSAGE, cause) {
    companion object {
        const val USER_MESSAGE = "T3 sent an unexpected response"
    }
}

/**
 * A websocket RPC failed. [message] is user-readable; the server's own text (an
 * `Exit` failure cause, a close reason) is kept in [detail] for diagnostics.
 */
open class T3RpcException(
    message: String,
    val detail: String? = null,
) : Exception(message) {
    companion object {
        const val REJECTED_MESSAGE = "T3 rejected the request"

        /** The server answered an RPC with an `Exit` failure. */
        fun rejected(cause: String): T3RpcException = T3RpcException(REJECTED_MESSAGE, detail = cause)

        /** Same, from the raw `cause` element: a string cause is kept unquoted. */
        fun rejected(cause: JsonElement): T3RpcException = rejected((cause as? JsonPrimitive)?.contentOrNull ?: cause.toString())
    }
}

/** The websocket is gone (closed, replaced, never opened, or timed out). */
class T3ConnectionLostException(
    detail: String? = null,
) : T3RpcException(USER_MESSAGE, detail) {
    companion object {
        const val USER_MESSAGE = "Lost connection to T3"
    }
}

object T3Errors {
    /**
     * Every T3 error type already carries a short user-readable [Throwable.message];
     * anything else that escapes the runtime (OkHttp, kotlinx.serialization,
     * JSON casts) is wrapped so raw library text never reaches a snackbar.
     */
    fun normalize(error: Throwable): Throwable =
        when (error) {
            is CancellationException,
            is T3HttpException,
            is T3ProtocolException,
            is T3TransportException,
            is T3DecodingException,
            is T3RpcException,
            -> error
            is IOException -> T3TransportException(error)
            else -> T3DecodingException(error.message, error)
        }

    /** The text a snackbar should show for [error]. */
    fun userMessage(error: Throwable): String = normalize(error).message ?: T3RpcException.REJECTED_MESSAGE

    /**
     * [message] for a diagnostic log line, with the server's own detail (an RPC
     * `Exit` failure cause, an HTTP body) appended when [error] carries one.
     */
    fun diagnosticMessage(
        message: String,
        error: Throwable,
    ): String = detail(error)?.takeIf { it.isNotBlank() }?.let { "$message detail=$it" } ?: message

    /** Diagnostic detail that must never be shown to the user, when the error carries one. */
    fun detail(error: Throwable): String? =
        when (error) {
            is T3HttpException -> error.body.takeIf { it.isNotBlank() }
            is T3DecodingException -> error.detail
            is T3RpcException -> error.detail
            is T3TransportException -> error.cause?.message
            else -> null
        }
}
