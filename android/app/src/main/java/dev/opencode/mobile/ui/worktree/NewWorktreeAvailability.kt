package dev.opencode.mobile.ui.worktree

import androidx.annotation.StringRes
import dev.opencode.mobile.R
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.ui.text.UiText

/** Whether a "New worktree…" row works, and why not. The first failing check explains it. */
data class NewWorktreeAvailability(
    val enabled: Boolean,
    val subtitle: UiText? = null,
) {
    companion object {
        /** The Run in row of Home's New session sheet. */
        fun runIn(
            worktreesEnabled: Boolean,
            isRepository: Boolean,
            projectId: String?,
            serverCanCreate: Boolean,
        ): NewWorktreeAvailability =
            when {
                !worktreesEnabled -> disabled(R.string.worktree_turn_on)
                else -> repositoryChecks(isRepository, projectId, serverCanCreate, R.string.worktree_run_in_subtitle)
            }

        /** The row of Session Chat's Move to worktree sheet. */
        fun move(
            supportsMove: Boolean,
            worktreesEnabled: Boolean,
            sessionCreated: Boolean,
            isRepository: Boolean,
            projectId: String?,
            serverCanCreate: Boolean,
        ): NewWorktreeAvailability =
            when {
                !supportsMove -> disabled(R.string.worktree_needs_v2)
                !worktreesEnabled -> disabled(R.string.worktree_turn_on)
                !sessionCreated -> NewWorktreeAvailability(false)
                else -> repositoryChecks(isRepository, projectId, serverCanCreate, R.string.worktree_move_subtitle)
            }

        fun runIn(
            project: HomeProject?,
            worktreesEnabled: Boolean,
            serverCanCreate: Boolean,
        ): NewWorktreeAvailability =
            runIn(
                worktreesEnabled = worktreesEnabled,
                isRepository = project?.hasVcs == true,
                projectId = project?.projectId,
                serverCanCreate = serverCanCreate,
            )

        /** A missing or `global` project id: a folder without a commit to start from. */
        fun isGlobal(projectId: String?): Boolean = projectId.isNullOrBlank() || projectId == HomeProjectGrouping.GLOBAL_PROJECT_ID

        private fun repositoryChecks(
            isRepository: Boolean,
            projectId: String?,
            serverCanCreate: Boolean,
            @StringRes enabledSubtitle: Int,
        ): NewWorktreeAvailability =
            when {
                !isRepository -> disabled(R.string.worktree_not_git)
                isGlobal(projectId) -> disabled(R.string.worktree_no_commit)
                !serverCanCreate -> disabled(R.string.worktree_unsupported)
                else -> NewWorktreeAvailability(true, UiText.resource(enabledSubtitle))
            }

        private fun disabled(
            @StringRes reason: Int,
        ): NewWorktreeAvailability = NewWorktreeAvailability(false, UiText.resource(reason))
    }
}
