package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeProjectGrouping
import dev.opencode.mobile.domain.worktree.WorktreeCreateCopy

/** Whether a "New worktree…" row works, and why not. The first failing check explains it. */
data class NewWorktreeAvailability(
    val enabled: Boolean,
    val subtitle: String? = null,
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
                !worktreesEnabled -> NewWorktreeAvailability(false, WorktreeCreateCopy.TURN_ON_WORKTREES)
                else -> repositoryChecks(isRepository, projectId, serverCanCreate, WorktreeCreateCopy.RUN_IN_ROW_SUBTITLE)
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
                !supportsMove -> NewWorktreeAvailability(false, WorktreeCreateCopy.NEEDS_V2)
                !worktreesEnabled -> NewWorktreeAvailability(false, WorktreeCreateCopy.TURN_ON_WORKTREES)
                !sessionCreated -> NewWorktreeAvailability(false)
                else -> repositoryChecks(isRepository, projectId, serverCanCreate, WorktreeCreateCopy.MOVE_ROW_SUBTITLE)
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
            enabledSubtitle: String,
        ): NewWorktreeAvailability =
            when {
                !isRepository -> NewWorktreeAvailability(false, WorktreeCreateCopy.NOT_GIT)
                isGlobal(projectId) -> NewWorktreeAvailability(false, WorktreeCreateCopy.NO_COMMIT)
                !serverCanCreate -> NewWorktreeAvailability(false, WorktreeCreateCopy.UNSUPPORTED)
                else -> NewWorktreeAvailability(true, enabledSubtitle)
            }
    }
}
