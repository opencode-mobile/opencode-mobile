package dev.opencode.mobile.domain.worktree

/** Removes a checkout other than the main one. V2 needs [projectId]; V1 asks in [projectDirectory]. */
data class RemoveWorktreeRequest(
    val projectId: String?,
    /** The project's main checkout: v1 sends it as `?directory=`. */
    val projectDirectory: String,
    val directory: String,
    /** V2: remove even with uncommitted changes. V1 always does. */
    val force: Boolean,
)

/** Remove failed; [message] is the server's own text when it gave one. */
sealed class WorktreeRemoveError(
    override val message: String,
) : Exception(message) {
    /** V2 refused because the checkout has uncommitted changes and the request did not force. */
    class ForceRequired(
        message: String,
    ) : WorktreeRemoveError(message)

    /** The server has no worktree remove route, or the project has no v2 id. */
    class Unsupported : WorktreeRemoveError(WorktreeCreateCopy.UNSUPPORTED)

    class Failed(
        message: String,
    ) : WorktreeRemoveError(message)
}
