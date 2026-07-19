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
    val createdAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null,
    val archivedAtEpochMs: Long? = null,
) {
    val isArchived: Boolean
        get() = archivedAtEpochMs != null
}

fun List<Session>.activeSessions(): List<Session> = filterNot { it.isArchived }

data class SessionSummary(
    val id: String,
    val title: String,
    val statusLabel: String? = null,
    val updatedAtLabel: String? = null,
    val updatedAtEpochMs: Long? = null,
)

data class SessionStatus(
    val sessionId: String,
    val raw: Map<String, String> = emptyMap(),
)

data class SessionTodo(
    val raw: String,
)

enum class FileDiffStatus {
    Added,
    Deleted,
    Modified,
}

data class FileDiff(
    val path: String,
    val patch: String?,
    val additions: Int,
    val deletions: Int,
    val status: FileDiffStatus,
)

data class VcsInfo(
    val currentBranch: String?,
    val defaultBranch: String?,
) {
    val supportsGit: Boolean
        get() = currentBranch != null || defaultBranch != null

    val hasDistinctMainBranch: Boolean
        get() = currentBranch != null && defaultBranch != null && currentBranch != defaultBranch
}

enum class VcsDiffMode(
    val wireValue: String,
) {
    WorkingTree("git"),
    MainBranch("branch"),
}

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

data class QuestionOption(
    val label: String,
    val description: String,
)

data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
)

data class QuestionRequest(
    val id: String,
    val sessionId: String,
    val questions: List<QuestionInfo>,
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
