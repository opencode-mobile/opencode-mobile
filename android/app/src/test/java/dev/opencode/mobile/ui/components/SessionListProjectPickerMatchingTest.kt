package dev.opencode.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListProjectPickerMatchingTest {
    @Test
    fun emptyQueryReturnsAllTabs() {
        assertEquals(sampleTabs, SessionListProjectPickerMatching.tabs(sampleTabs, query = "  "))
    }

    @Test
    fun matchesTitle() {
        val matches = SessionListProjectPickerMatching.tabs(sampleTabs, query = "play")
        assertEquals(listOf("/play"), matches.map { it.id })
    }

    @Test
    fun matchesWorktreePath() {
        val matches = SessionListProjectPickerMatching.tabs(sampleTabs, query = "/repo")
        assertEquals(listOf("/repo"), matches.map { it.id })
    }

    @Test
    fun matchesAllProjectsLabel() {
        val matches = SessionListProjectPickerMatching.tabs(sampleTabs, query = "all")
        assertEquals(listOf(SessionListFilterTab.ALL_ID), matches.map { it.id })
    }

    @Test
    fun noMatchesReturnsEmpty() {
        assertTrue(SessionListProjectPickerMatching.tabs(sampleTabs, query = "zzz").isEmpty())
    }

    private val sampleTabs =
        listOf(
            SessionListFilterTab.all(projectCount = 2, sessionCount = 3),
            SessionListFilterTab.project(worktree = "/repo", title = "opencode-mobile", count = 2),
            SessionListFilterTab.project(worktree = "/play", title = "playground", count = 1),
        )
}
