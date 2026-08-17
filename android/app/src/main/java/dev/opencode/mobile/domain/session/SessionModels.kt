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
    val revert: SessionRevert? = null,
) {
    val isArchived: Boolean
        get() = archivedAtEpochMs != null
}

data class SessionRevert(
    val messageId: String,
    val partId: String? = null,
    val snapshot: String? = null,
    val diff: String? = null,
)

fun List<Session>.activeSessions(): List<Session> = filterNot { it.isArchived }

fun List<Session>.rootSessions(): List<Session> = filter { it.parentId == null }

data class SessionSummary(
    val id: String,
    val title: String,
    val statusLabel: String? = null,
    val updatedAtLabel: String? = null,
    val updatedAtEpochMs: Long? = null,
    val isReady: Boolean = false,
)

data class SessionStatus(
    val sessionId: String,
    val raw: Map<String, String> = emptyMap(),
)

enum class SessionTodoStatus {
    Pending,
    InProgress,
    Completed,
    Cancelled,
    ;

    companion object {
        fun fromRaw(raw: String): SessionTodoStatus =
            when (raw.lowercase()) {
                "in_progress", "in-progress", "inprogress" -> InProgress
                "completed", "complete", "done" -> Completed
                "cancelled", "canceled" -> Cancelled
                else -> Pending
            }
    }
}

data class SessionTodo(
    /** List order is identity; no server id. */
    val position: Int,
    val content: String,
    val status: SessionTodoStatus,
    val priority: String,
)

object SessionTodoList {
    fun completedCount(todos: List<SessionTodo>): Int = todos.count { it.status == SessionTodoStatus.Completed }

    fun isFinished(todos: List<SessionTodo>): Boolean =
        todos.isNotEmpty() &&
            todos.all {
                it.status == SessionTodoStatus.Completed || it.status == SessionTodoStatus.Cancelled
            }

    fun activePreview(todos: List<SessionTodo>): SessionTodo? =
        todos.firstOrNull { it.status == SessionTodoStatus.InProgress }
            ?: todos.firstOrNull { it.status == SessionTodoStatus.Pending }
            ?: todos.lastOrNull { it.status == SessionTodoStatus.Completed }
            ?: todos.firstOrNull()

    /** Visible while non-empty and not fully finished, including after idle. */
    fun isVisible(todos: List<SessionTodo>): Boolean = todos.isNotEmpty() && !isFinished(todos)

    /** Hold the all-done pill only after this presentation already showed an unfinished list. */
    fun shouldHoldFinished(
        sawUnfinished: Boolean,
        todos: List<SessionTodo>,
    ): Boolean = sawUnfinished && isFinished(todos)
}

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
    ;

    val v2WireValue: String
        get() =
            when (this) {
                WorkingTree -> "working"
                MainBranch -> "branch"
            }
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
    val fieldKey: String? = null,
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
