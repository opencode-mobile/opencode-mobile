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

    private fun project(
        id: String,
        worktree: String,
        activity: Long?,
    ): Project =
        Project(
            id = id,
            worktree = worktree,
            displayName = ProjectDomainAdapter.displayNameFromWorktree(worktree),
            activityAtEpochMs = activity,
        )
}
