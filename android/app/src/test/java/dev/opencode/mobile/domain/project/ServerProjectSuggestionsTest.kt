package dev.opencode.mobile.domain.project

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerProjectSuggestionsTest {
    @Test
    fun excludesGlobalAndRootProjects() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("global", "/", 10),
                        project("global", "/Users/me", 11),
                        project("root", "/", 12),
                        project("app", "/Users/me/app", 1),
                    ),
                addedWorktrees = emptyList(),
            )

        assertEquals(listOf("/Users/me/app"), result.map { it.worktree })
    }

    @Test
    fun excludesProjectsAlreadyOnHome() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("a", "/Users/me/app", 2),
                        project("b", "/Users/me/web", 1),
                    ),
                addedWorktrees = listOf("/Users/me/app/"),
            )

        assertEquals(listOf("/Users/me/web"), result.map { it.worktree })
    }

    @Test
    fun dedupesByWorktreeKeepingNewest() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("old", "/Users/me/app", 1),
                        project("new", "/Users/me/app", 5),
                        project("undated", "/Users/me/app", null),
                    ),
                addedWorktrees = emptyList(),
            )

        assertEquals(listOf("new"), result.map { it.id })
    }

    @Test
    fun sortsNewestFirstWithUndatedLast() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("undated", "/srv/undated", null),
                        project("older", "/srv/older", 10),
                        project("newest", "/srv/newest", 30),
                        project("middle", "/srv/middle", 20),
                    ),
                addedWorktrees = emptyList(),
            )

        assertEquals(listOf("newest", "middle", "older", "undated"), result.map { it.id })
    }

    @Test
    fun capsAtFiveNewest() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects = (1L..8L).map { project("p$it", "/srv/p$it", it) },
                addedWorktrees = emptyList(),
            )

        assertEquals(ServerProjectSuggestions.MAX_SUGGESTIONS, result.size)
        assertEquals(listOf("p8", "p7", "p6", "p5", "p4"), result.map { it.id })
    }

    @Test
    fun excludesFoldersWithoutVersionControl() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("folder", "/Users/me/notes", 2, repository = false),
                        project("repo", "/Users/me/app", 1),
                    ),
                addedWorktrees = emptyList(),
            )

        assertEquals(listOf("/Users/me/app"), result.map { it.worktree })
    }

    @Test
    fun excludesTempAndHiddenFolders() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("upload", "/private/var/folders/_p/x/T/opencode/uploads/8d82", 9),
                        project("var", "/var/folders/_p/x/T/repo", 8),
                        project("tmp", "/tmp/repo", 7),
                        project("var-tmp", "/private/var/tmp/repo", 7),
                        project("scratch", "/Users/me/.t3/scratch", 6),
                        project("dot", "/Users/me/.jenv", 5),
                        project("win-temp", """C:\Users\me\AppData\Local\Temp\repo""", 4),
                        project("tmpish", "/Users/me/tmp-tools", 3),
                        project("app", "/Users/me/app", 2),
                    ),
                addedWorktrees = emptyList(),
            )

        assertEquals(listOf("/Users/me/tmp-tools", "/Users/me/app"), result.map { it.worktree })
    }

    /** A real server's list: agents and tools left plain-folder projects that outrank the repos. */
    @Test
    fun suggestsReposNotOnHomeFromAgentCreatedServerList() {
        val result =
            ServerProjectSuggestions.suggestions(
                projects =
                    listOf(
                        project("opencode", "/Users/ivan/Projects/opencode", 20),
                        project("mobile", "/Users/ivan/Projects/opencode-mobile", 19),
                        project("home", "/Users/ivan", 18, repository = false),
                        project("t3code", "/Users/ivan/Projects/t3code", 17),
                        project("root", "/", 16, repository = false),
                        project("scratch", "/Users/ivan/.t3/scratch", 15, repository = false),
                        project("projects", "/Users/ivan/Projects", 14, repository = false),
                        project("evidence", "/Users/ivan/Projects/oc-655-evidence", 13, repository = false),
                        project("upload", "/private/var/folders/_p/drt8/T/opencode/uploads/8d82a3f4", 12, repository = false),
                        project("global", "/", 11, repository = false),
                        project("instax", "/Users/ivan/Projects/INSTAX", 10),
                        project("homelab", "/Users/ivan/Projects/homelab", 9),
                        project("difftest", "/Users/ivan/Projects/DiffTest", 8),
                        project("docs", "/Users/ivan/Projects/opencode-mobile-docs", 7),
                        project("jenv", "/Users/ivan/.jenv", 6),
                    ),
                addedWorktrees =
                    listOf(
                        "/Users/ivan/Projects/opencode",
                        "/Users/ivan/Projects/opencode-mobile",
                        "/Users/ivan",
                        "/Users/ivan/Projects/t3code",
                    ),
            )

        assertEquals(listOf("INSTAX", "homelab", "DiffTest", "opencode-mobile-docs"), result.map { it.displayName })
    }

    private fun project(
        id: String,
        worktree: String,
        activity: Long?,
        repository: Boolean = true,
    ): Project =
        Project(
            id = id,
            worktree = worktree,
            displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
            activityAtEpochMs = activity,
            isRepository = repository,
        )
}
