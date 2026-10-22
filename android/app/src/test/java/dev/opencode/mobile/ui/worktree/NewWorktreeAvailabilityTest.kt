package dev.opencode.mobile.ui.worktree

import dev.opencode.mobile.domain.home.HomeProject
import dev.opencode.mobile.domain.home.HomeWorktree
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
            NewWorktreeAvailability(false, "Turn on Worktrees in Settings"),
            runIn(on = false, git = false, projectId = null, server = false),
        )
        assertEquals(NewWorktreeAvailability(false, "Needs a git repository"), runIn(git = false, projectId = null, server = false))
        assertEquals(NewWorktreeAvailability(false, "Needs a git repository with a commit"), runIn(projectId = "global", server = false))
        assertEquals(NewWorktreeAvailability(false, "Needs a git repository with a commit"), runIn(projectId = null))
        assertEquals(NewWorktreeAvailability(false, "This opencode server can't create worktrees"), runIn(server = false))
        assertEquals(NewWorktreeAvailability(true, "Create a checkout and start there"), runIn())
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
            NewWorktreeAvailability(false, "Needs an opencode v2 server"),
            move(v2 = false, on = false, created = false, git = false, server = false),
        )
        assertEquals(NewWorktreeAvailability(false, "Turn on Worktrees in Settings"), move(on = false, created = false, git = false))
        assertEquals(NewWorktreeAvailability(false, null), move(created = false, git = false))
        assertEquals(NewWorktreeAvailability(false, "Needs a git repository"), move(git = false, projectId = "global"))
        assertEquals(NewWorktreeAvailability(false, "Needs a git repository with a commit"), move(projectId = "global", server = false))
        assertEquals(NewWorktreeAvailability(false, "This opencode server can't create worktrees"), move(server = false))
        assertEquals(NewWorktreeAvailability(true, "Create a checkout from this one and move there"), move())
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
        assertEquals("Needs a git repository with a commit", NewWorktreeAvailability.runIn(noCommit, true, true).subtitle)
        assertEquals("Needs a git repository", NewWorktreeAvailability.runIn(plain, true, true).subtitle)
        assertEquals("Needs a git repository", NewWorktreeAvailability.runIn(null, true, true).subtitle)
    }
}
