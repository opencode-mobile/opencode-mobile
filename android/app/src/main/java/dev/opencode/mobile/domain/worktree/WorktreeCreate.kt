package dev.opencode.mobile.domain.worktree

/** A new checkout of a project. V1 ignores [projectId] and [baseRef]; V2 needs [projectId]. */
data class CreateWorktreeRequest(
    val projectId: String?,
    /** The checkout the new one is made from (v1: the project checkout the server is asked in). */
    val sourceDirectory: String,
    val name: String,
    /** V2 start ref; null starts from [sourceDirectory]'s HEAD. */
    val baseRef: String?,
)

data class CreatedWorktree(
    /** Canonical directory of the new checkout. */
    val directory: String,
    /** The name the server used, which may differ from the requested one. */
    val name: String,
    /** V1 `opencode/<name>`; null for a detached v2 checkout. */
    val branch: String?,
)

/** Create failed; [message] is the server's own text when it gave one. */
class WorktreeCreateError(
    override val message: String,
) : Exception(message)

/**
 * Domain error text the worktree repository throws. The form shows it inside its own keyed
 * "Couldn't create the worktree: …" message; app text of New worktree lives in strings.xml
 * (`worktree_*`).
 */
object WorktreeCreateCopy {
    const val NOT_GIT = "Needs a git repository"
    const val UNSUPPORTED = "This server can't create worktrees"

    fun requestFailed(statusCode: Int): String = "Request failed (HTTP $statusCode)"
}
