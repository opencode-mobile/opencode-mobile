package dev.opencode.mobile.domain.session

data class Session(
    val id: String,
    val title: String,
    val slug: String? = null,
    val projectId: String? = null,
    val directory: String? = null,
    val parentId: String? = null,
    val updatedAtEpochMs: Long? = null,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val statusLabel: String? = null,
    val updatedAtLabel: String? = null,
)

data class SessionStatus(
    val sessionId: String,
    val raw: Map<String, String> = emptyMap(),
)

data class SessionTodo(
    val raw: String,
)

data class FileDiff(
    val raw: String,
)

enum class PermissionDecision {
    Approve,
    Deny,
    Once,
}

sealed class SessionError : Exception() {
    data class Transport(
        override val cause: Throwable,
    ) : SessionError()

    data class Http(
        val statusCode: Int,
        override val message: String?,
    ) : SessionError()

    data class Decoding(
        override val cause: Throwable,
    ) : SessionError()
}
