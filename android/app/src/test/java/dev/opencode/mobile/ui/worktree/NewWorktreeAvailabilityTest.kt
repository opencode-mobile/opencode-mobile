package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.R
import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class NewWorktreeAvailabilityTest {
    @Test
    fun runInRowChecksSettingThenGitThenCommitThenServer() {
        fun runIn(
            on: Boolean = true,
            git: Boolean = true,
            projectId: String? = "proj",
            server: Boolean = true,
        ) = NewWorktreeAvailability.runIn(on, git, projectId, server)

        assertEquals(
            NewWorktreeAvailability(false, UiText.resource(R.string.worktree_turn_on)),
            runIn(on = false, git = false, projectId = null, server = false),
        )
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_not_git)), runIn(git = false, projectId = null, server = false))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_no_commit)), runIn(projectId = "global", server = false))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_no_commit)), runIn(projectId = null))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_unsupported)), runIn(server = false))
        assertEquals(NewWorktreeAvailability(true, UiText.resource(R.string.worktree_run_in_subtitle)), runIn())
    }

    @Test
    fun moveRowChecksV2ThenSettingThenSessionThenGitThenCommitThenServer() {
        fun move(
            v2: Boolean = true,
            on: Boolean = true,
            created: Boolean = true,
            git: Boolean = true,
            projectId: String? = "proj",
            server: Boolean = true,
        ) = NewWorktreeAvailability.move(v2, on, created, git, projectId, server)

        assertEquals(
            NewWorktreeAvailability(false, UiText.resource(R.string.worktree_needs_v2)),
            move(v2 = false, on = false, created = false, git = false, server = false),
        )
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_turn_on)), move(on = false, created = false, git = false))
        assertEquals(NewWorktreeAvailability(false, null), move(created = false, git = false))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_not_git)), move(git = false, projectId = "global"))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_no_commit)), move(projectId = "global", server = false))
        assertEquals(NewWorktreeAvailability(false, UiText.resource(R.string.worktree_unsupported)), move(server = false))
        assertEquals(NewWorktreeAvailability(true, UiText.resource(R.string.worktree_move_subtitle)), move())
    }

    @Test
    fun runInReadsTheHomeProject() {
        val repo =
            HomeProject(
                key = "proj",
                projectId = "proj",
                displayName = "repo",
                memberPaths = listOf("/repo"),
                isRepository = true,
                worktrees = listOf(HomeWorktree("/repo", isMain = true)),
            )
        // A git folder without a commit resolves to `global` and does not merge, but has version control.
        val noCommit =
            HomeProject(
                key = "path:/fresh",
                projectId = null,
                displayName = "fresh",
                memberPaths = listOf("/fresh"),
                isRepository = false,
                worktrees = listOf(HomeWorktree("/fresh", isMain = true)),
                hasVcs = true,
            )
        val plain = noCommit.copy(hasVcs = false)

        assertEquals(true, NewWorktreeAvailability.runIn(repo, worktreesEnabled = true, serverCanCreate = true).enabled)
        assertEquals(UiText.resource(R.string.worktree_no_commit), NewWorktreeAvailability.runIn(noCommit, true, true).subtitle)
        assertEquals(UiText.resource(R.string.worktree_not_git), NewWorktreeAvailability.runIn(plain, true, true).subtitle)
        assertEquals(UiText.resource(R.string.worktree_not_git), NewWorktreeAvailability.runIn(null, true, true).subtitle)
    }
}
