package dev.opencode.mobile.domain.t3

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

class T3ProtocolException(
    message: String,
) : Exception(message)

class T3RpcException(
    message: String,
) : Exception(message)
