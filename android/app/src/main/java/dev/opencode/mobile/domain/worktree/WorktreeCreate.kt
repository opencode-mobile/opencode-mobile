package dev.opencode.mobile.domain.worktree

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.text.UiText

/** A new checkout of a project. V1 ignores [projectId] and [baseRef]; V2 needs [projectId]. */
data class CreateWorktreeRequest(
    val projectId: String?,
    /** The checkout the new one is made from (v1: the project checkout the server is asked in). */
    val sourceDirectory: String,
    /** Blank lets the server name the checkout (the New session draft). */
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

/**
 * Create failed. [text] is what the app shows: the server's own words verbatim when it gave
 * some, else keyed app text. [message] is the same for logs (no app text).
 */
class WorktreeCreateError(
    override val message: String,
    val text: UiText = UiText.verbatim(message),
) : Exception(message)

/**
 * Domain error text the worktree repository throws. The form shows it inside its own keyed
 * "Couldn't create the worktree: …" message; app text of New worktree lives in strings.xml
 * (`worktree_*`).
 */
object WorktreeCreateCopy {
    const val NOT_GIT = "Needs a git repository"
    const val UNSUPPORTED = "This server can't create worktrees"

    /** "Request failed (HTTP 500)": the server answered [statusCode] with nothing readable. */
    fun requestFailed(statusCode: Int): UiText = UiText.resource(R.string.worktree_request_failed_http, statusCode)
}
