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

/** Copy of New worktree (Run in, Move to worktree, and the form); identical on iOS. */
object WorktreeCreateCopy {
    const val NEW_WORKTREE_ROW = "New worktree…"
    const val RUN_IN_ROW_SUBTITLE = "Create a checkout and start there"
    const val MOVE_ROW_SUBTITLE = "Create a checkout from this one and move there"
    const val NEEDS_V2 = "Needs an opencode v2 server"
    const val TURN_ON_WORKTREES = "Turn on Worktrees in Settings"
    const val NOT_GIT = "Needs a git repository"
    const val NO_COMMIT = "Needs a git repository with a commit"
    const val UNSUPPORTED = "This opencode server can't create worktrees"
    const val FORM_TITLE = "New worktree"
    const val NAME_LABEL = "Name"
    const val BRANCH_LABEL = "Branch"
    const val BASE_LABEL = "Create from"
    const val BACK = "Back"
    const val V1_BRANCH_NOTE = "opencode v1 names the branch after the worktree"
    const val V2_NO_BRANCH = "No branch (detached)"
    const val V2_BRANCH_NOTE = "opencode v2 creates a detached checkout, shown by its folder name"
    const val CURRENT_CHECKOUT = "Current checkout"
    const val V1_BASE_NOTE = "opencode v1 creates from the project's current checkout"
    const val BASE_PICK_HINT = "Tap to pick a branch"
    const val CURRENT_SUFFIX = " (current)"
    const val BASE_LOADING = "Loading branches…"
    const val BASE_FAILED = "Couldn't load branches"
    const val NAME_EMPTY = "Enter a name"
    const val NAME_CHARS = "Use letters, numbers, dots, dashes or underscores"
    const val NAME_LONG = "Use at most 64 characters"
    const val SUBMIT_RUN_IN = "Create and start"
    const val SUBMIT_MOVE = "Create and move"
    const val SUBMITTING = "Creating…"

    fun failed(message: String): String = "Couldn't create the worktree: $message"

    fun requestFailed(statusCode: Int): String = "Request failed (HTTP $statusCode)"

    /** V1 branch: `opencode/<name>`, or `opencode/…` while the name is empty. */
    fun v1Branch(name: String): String = "opencode/" + name.trim().ifEmpty { "…" }

    /** The default base: the source checkout's branch marked current, else [CURRENT_CHECKOUT]. */
    fun defaultBase(sourceBranch: String?): String = sourceBranch?.let { it + CURRENT_SUFFIX } ?: CURRENT_CHECKOUT
}
