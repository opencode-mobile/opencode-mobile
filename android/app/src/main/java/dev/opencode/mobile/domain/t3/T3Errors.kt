package dev.opencode.mobile.domain.t3

class T3HttpException(
    val statusCode: Int,
    val body: String,
) : Exception(messageFor(statusCode, body)) {
    companion object {
        fun messageFor(
            statusCode: Int,
            body: String,
        ): String {
            val trimmed = body.trim()
            return if (trimmed.isNotEmpty()) trimmed else "T3 request failed ($statusCode)"
        }
    }
}

class T3ProtocolException(
    message: String,
) : Exception(message)

class T3RpcException(
    message: String,
) : Exception(message)
