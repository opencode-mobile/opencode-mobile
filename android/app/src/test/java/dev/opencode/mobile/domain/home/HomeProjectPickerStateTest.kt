package dev.opencode.mobile.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProjectPickerStateTest {
    @Test
    fun accordionKeepsOneProjectOpen() {
        val opened = HomeProjectPickerState().toggleExpanded("a")
        assertEquals("a", opened.expandedProjectKey)

        val switched = opened.toggleExpanded("b")
        assertEquals("b", switched.expandedProjectKey)
        assertFalse(switched.isExpanded("a"))

        assertNull(switched.toggleExpanded("b").expandedProjectKey)
    }

    @Test
    fun showAllPastFiveAndShowFewer() {
        val worktrees = (1..7).toList()
        val state = HomeProjectPickerState().toggleExpanded("a")

        assertEquals(5, state.visibleWorktrees("a", worktrees).size)
        assertEquals("Show all 7", state.showAllLabel("a", worktrees.size))
        val all = state.toggleShowAll("a")
        assertEquals(7, all.visibleWorktrees("a", worktrees).size)
        assertEquals("Show fewer", all.showAllLabel("a", worktrees.size))
        assertEquals(5, all.toggleShowAll("a").visibleWorktrees("a", worktrees).size)
        // Five or fewer: every worktree, no Show all row.
        assertEquals(5, state.visibleWorktrees("a", (1..5).toList()).size)
        assertNull(state.showAllLabel("a", 5))
    }

    @Test
    fun editCollapsesEverythingAndStaysCollapsedAfterDone() {
        val busy = HomeProjectPickerState().toggleExpanded("a").toggleShowAll("a")

        val editing = busy.setEditing(true)
        assertTrue(editing.isEditing)
        assertNull(editing.expandedProjectKey)
        assertTrue(editing.showAllProjectKeys.isEmpty())
        assertFalse(editing.toggleExpanded("a").isExpanded("a"))

        val done = editing.setEditing(false)
        assertFalse(done.isEditing)
        assertNull(done.expandedProjectKey)
    }

    @Test
    fun initialExpandsTheSelectedWorktreesProject() {
        val project =
            HomeProject(
                key = "proj",
                projectId = "proj",
                displayName = "repo",
                memberPaths = listOf("/r"),
                isRepository = true,
                worktrees = (0..6).map { HomeWorktree(directory = "/r$it", isMain = it == 0) },
            )

        val early = HomeProjectPickerState.initial(HomeScope.Worktree("proj", "/r2"), listOf(project))
        assertEquals("proj", early.expandedProjectKey)
        assertTrue(early.showAllProjectKeys.isEmpty())

        val late = HomeProjectPickerState.initial(HomeScope.Worktree("proj", "/r6"), listOf(project))
        assertEquals(setOf("proj"), late.showAllProjectKeys)

        assertEquals(HomeProjectPickerState(), HomeProjectPickerState.initial(HomeScope.Project("proj"), listOf(project)))
        assertEquals(HomeProjectPickerState(), HomeProjectPickerState.initial(HomeScope.All, listOf(project)))
    }
}
