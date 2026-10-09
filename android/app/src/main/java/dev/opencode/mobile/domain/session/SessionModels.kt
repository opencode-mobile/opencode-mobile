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
    val statusStale: Boolean = false,
)

data class SessionStatus(
    val sessionId: String,
    val raw: Map<String, String> = emptyMap(),
)

sealed class SessionActivity {
    data object Idle : SessionActivity()

    data object Running : SessionActivity()

    data class Retrying(
        val attempt: Int? = null,
        val nextAtMs: Long? = null,
        val message: String? = null,
    ) : SessionActivity()

    fun isBusy(): Boolean = this is Running || this is Retrying

    fun statusLabel(): String? =
        when (this) {
            Idle -> null
            Running -> "Working"
            is Retrying -> if (attempt != null) "Retrying · $attempt" else "Retrying"
        }

    /**
     * In-chat label. The home list keeps [statusLabel] ("Retrying · N") because
     * it parses the attempt off that exact prefix.
     */
    fun chatStatusLabel(): String? {
        val base = statusLabel()
        if (this !is Retrying) return base
        val reason = message?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: return base
        return "${base ?: "Retrying"} · $reason"
    }
}

enum class SessionActivitySource {
    Server,
    Optimistic,
    Cached,
}

data class SessionActivityEntry(
    val activity: SessionActivity,
    val source: SessionActivitySource,
    val observedAtMs: Long,
)

data class SessionActivitySnapshot(
    val entries: Map<String, SessionActivityEntry> = emptyMap(),
    val seeded: Boolean = false,
    val fromCache: Boolean = false,
)

fun SessionStatus.toSessionActivity(): SessionActivity =
    when (raw["type"]?.lowercase()) {
        "busy", "running" -> SessionActivity.Running
        "retry" ->
            SessionActivity.Retrying(
                attempt = raw["attempt"]?.toIntOrNull(),
                nextAtMs =
                    raw["next"]?.toLongOrNull()
                        ?: raw["nextAt"]?.toLongOrNull()
                        ?: raw["nextAtMs"]?.toLongOrNull(),
                message = raw["message"],
            )
        else -> SessionActivity.Idle
    }

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

/** One uncommitted working-copy change reported by the server VCS status endpoint. */
data class VcsFileStatus(
    val path: String,
    val status: FileDiffStatus,
    val additions: Int,
    val deletions: Int,
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

    /** Allow and save the request's `always` patterns (v1 `always`, v2 `decision: "always"`). */
    AlwaysAllow,
    Deny,
}

/** One file an edit/write permission would change, from the request metadata. */
data class PermissionFileChange(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: JsonObject,
    val always: List<String>,
    val toolMessageId: String?,
    val toolCallId: String?,
) {
    /** Files an edit/write would change; empty for other permissions. */
    val fileChanges: List<PermissionFileChange>
        get() = PermissionFileChanges.from(metadata)
}

data class QuestionOption(
    val label: String,
    val description: String,
    val value: String = label,
)

data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
    val fieldKey: String? = null,
    /** Form multiselect bounds (`minItems` / `maxItems`); null means unbounded. */
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
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
        /** Raw response body, when it was read. */
        val body: String? = null,
    ) : SessionError()

    data class Decoding(
        override val cause: Throwable,
    ) : SessionError()
}
