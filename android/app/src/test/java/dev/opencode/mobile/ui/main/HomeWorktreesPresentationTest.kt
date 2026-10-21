package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.event.SessionMovedUpdate
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.ResolvedHomePath
import dev.opencode.mobile.domain.session.SessionSummary
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.SessionListLocationPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home rows with the Worktrees setting on and off, and after a session moves. */
class HomeWorktreesPresentationTest {
    private val loaded =
        MainScreenUiState.Loaded(
            sections =
                listOf(
                    ProjectSectionUi(
                        worktree = "/repo",
                        displayName = "repo",
                        pathSubtitle = "/repo",
                        letter = 'R',
                        letterAccent = LetterAccent.Violet,
                        allSessions =
                            listOf(
                                SessionSummary(id = "ses-1", title = "On main", directory = "/repo", updatedAtEpochMs = 20L),
                                SessionSummary(id = "ses-2", title = "In feature", directory = "/repo-feature", updatedAtEpochMs = 10L),
                            ),
                    ),
                ),
            home =
                HomeWorktreeIndex(
                    resolutions = mapOf("/repo" to ResolvedHomePath("proj", "/repo", name = "repo", isRepository = true)),
                    inventories = mapOf("/repo" to setOf("/repo", "/repo-feature")),
                    branches = mapOf("/repo" to "dev", "/repo-feature" to "feature/x"),
                ),
        )

    @Test
    fun worktreesOnRowsCarryBranchFolderAndWorktreeScope() {
        val rows = loaded.homeSessions(worktreesEnabled = true).associateBy { it.id }

        assertEquals(HomeScope.worktreeId("proj", "/repo-feature"), rows.getValue("ses-2").worktreeScopeId)
        assertEquals(
            listOf(SessionListLocationPart("feature/x", isBranch = true), SessionListLocationPart("repo-feature")),
            rows.getValue("ses-2").location,
        )
        assertEquals(listOf(SessionListLocationPart("dev", isBranch = true)), rows.getValue("ses-1").location)
    }

    @Test
    fun worktreesOffRowsShowTheProjectLikeANonRepositoryAndKeepEverySession() {
        val rows = loaded.homeSessions(worktreesEnabled = false)

        assertEquals(listOf("ses-1", "ses-2"), rows.map { it.id })
        rows.forEach { row ->
            assertNull(row.location)
            assertNull(row.worktreeScopeId)
            assertEquals("proj", row.worktree)
        }
        val worktreeId = HomeScope.worktreeId("proj", "/repo-feature")
        assertEquals("proj", homeScopeId(worktreeId, loaded.projects, worktreesEnabled = false))
        assertEquals(worktreeId, homeScopeId(worktreeId, loaded.projects, worktreesEnabled = true))
    }

    @Test
    fun sessionMovedRegroupsTheRowUnderItsNewWorktree() {
        val moved = loaded.withSessionMoved(SessionMovedUpdate("ses-1", "/repo-feature"))
        val row = moved.homeSessions().single { it.id == "ses-1" }

        assertEquals("/repo-feature", row.directory)
        assertEquals(HomeScope.worktreeId("proj", "/repo-feature"), row.worktreeScopeId)
        assertEquals(
            "/repo-feature",
            moved.sections
                .single()
                .allSessions
                .single { it.id == "ses-1" }
                .directory,
        )
        // Other rows stay put.
        assertEquals(loaded.sections.single().allSessions[1], moved.sections.single().allSessions[1])
    }
}
