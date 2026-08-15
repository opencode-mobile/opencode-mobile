package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.components.SessionListFilterTab
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListHomeChromeTest {
    @Test
    fun filterTabs_zeroProjectsOmitsAllAndChipRow() {
        val tabs = SessionListHomeChrome.filterTabs(projects = emptyList(), sessionCount = 0)
        assertEquals(emptyList<SessionListFilterTab>(), tabs)
    }

    @Test
    fun filterTabs_oneProjectShowsOnlyThatChip() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), sessionCount = 2)
        assertEquals(
            listOf(SessionListFilterTab(id = "/repo", title = "opencode-mobile", count = 2)),
            tabs,
        )
    }

    @Test
    fun filterTabs_twoProjectsPrependsAll() {
        val tabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                sessionCount = 3,
            )
        assertEquals(
            listOf(
                SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 3),
                SessionListFilterTab(id = "/repo", title = "opencode-mobile", count = 2),
                SessionListFilterTab(id = "/play", title = "playground", count = 1),
            ),
            tabs,
        )
    }

    @Test
    fun resolvedFilterId_fallsBackToSoleProjectWhenAllIsOmitted() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), sessionCount = 2)
        assertEquals(
            "/repo",
            SessionListHomeChrome.resolvedFilterId(
                selectedId = SessionListFilterTab.ALL_ID,
                tabs = tabs,
            ),
        )
    }

    @Test
    fun resolvedFilterId_writeBackThenSecondProjectKeepsSoleProject() {
        val oneProjectTabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), sessionCount = 2)
        val storedId =
            SessionListHomeChrome.resolvedFilterId(
                selectedId = SessionListFilterTab.ALL_ID,
                tabs = oneProjectTabs,
            )
        val twoProjectTabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                sessionCount = 3,
            )
        assertEquals(
            "/repo",
            SessionListHomeChrome.resolvedFilterId(selectedId = storedId, tabs = twoProjectTabs),
        )
    }

    @Test
    fun resolvedFilterId_keepsProjectWhenAllReappears() {
        val tabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                sessionCount = 3,
            )
        assertEquals(
            "/repo",
            SessionListHomeChrome.resolvedFilterId(selectedId = "/repo", tabs = tabs),
        )
    }

    @Test
    fun resolvedFilterId_fallsBackToAllWhenTwoPlusAndSelectionMissing() {
        val tabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                sessionCount = 3,
            )
        assertEquals(
            SessionListFilterTab.ALL_ID,
            SessionListHomeChrome.resolvedFilterId(selectedId = "/gone", tabs = tabs),
        )
    }

    @Test
    fun newSessionAction_zeroProjectsDoesNothing() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.None,
            SessionListHomeChrome.newSessionAction(emptyList()),
        )
    }

    @Test
    fun newSessionAction_oneProjectCreatesInThatWorktree() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.newSessionAction(listOf(mobile)),
        )
    }

    @Test
    fun newSessionAction_twoPlusPresentsPicker() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentPicker,
            SessionListHomeChrome.newSessionAction(listOf(mobile, playground)),
        )
    }

    private val mobile =
        SessionListHomeChrome.Project(
            worktree = "/repo",
            title = "opencode-mobile",
            sessionCount = 2,
        )
    private val playground =
        SessionListHomeChrome.Project(
            worktree = "/play",
            title = "playground",
            sessionCount = 1,
        )
}
