package dev.opencode.mobile.domain.session

import kotlinx.serialization.json.JsonObject

data class Session(
    val id: String,
    val title: String,
    val slug: String? = null,
    val projectId: String? = null,
    val directory: String? = null,
    val parentId: String? = null,
    val cost: Double? = null,
    val tokens: dev.opencode.mobile.domain.message.MessageTokenUsage? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val variant: String? = null,
    val createdAtEpochMs: Long? = null,
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
    AllowOnce,
    Deny,
}

data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: JsonObject,
    val always: List<String>,
    val toolMessageId: String?,
    val toolCallId: String?,
)

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
