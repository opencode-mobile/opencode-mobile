package dev.opencode.mobile.ui.main

import dev.opencode.mobile.ui.components.SessionListFilterTab
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListHomeChromeTest {
    @Test
    fun filterTabs_zeroProjectsOmitsAllAndScopeControl() {
        val tabs = SessionListHomeChrome.filterTabs(projects = emptyList(), sessionCount = 0)
        assertEquals(emptyList<SessionListFilterTab>(), tabs)
    }

    @Test
    fun filterTabs_oneProjectShowsOnlyThatProject() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), sessionCount = 2)
        assertEquals(
            listOf(SessionListFilterTab.project(worktree = "/repo", title = "opencode-mobile", count = 2)),
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
                SessionListFilterTab.all(projectCount = 2, sessionCount = 3),
                SessionListFilterTab.project(worktree = "/repo", title = "opencode-mobile", count = 2),
                SessionListFilterTab.project(worktree = "/play", title = "playground", count = 1),
            ),
            tabs,
        )
        assertEquals("All", tabs.first().title)
        assertEquals("All projects", tabs.first().pickerTitle)
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
            SessionListHomeChrome.newSessionAction(
                projects = emptyList(),
                selectedFilterId = SessionListFilterTab.ALL_ID,
            ),
        )
    }

    @Test
    fun newSessionAction_oneProjectCreatesInThatWorktree() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.newSessionAction(
                projects = listOf(mobile),
                selectedFilterId = "/repo",
            ),
        )
    }

    @Test
    fun newSessionAction_allSelectedPresentsPicker() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentPicker,
            SessionListHomeChrome.newSessionAction(
                projects = listOf(mobile, playground),
                selectedFilterId = SessionListFilterTab.ALL_ID,
            ),
        )
    }

    @Test
    fun newSessionAction_scopedProjectCreatesThere() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/play"),
            SessionListHomeChrome.newSessionAction(
                projects = listOf(mobile, playground),
                selectedFilterId = "/play",
            ),
        )
    }

    @Test
    fun terminalAction_zeroProjectsHides() {
        assertEquals(
            SessionListHomeChrome.TerminalAction.None,
            SessionListHomeChrome.terminalAction(projects = emptyList()),
        )
    }

    @Test
    fun terminalAction_oneProjectOpensCatalog() {
        assertEquals(
            SessionListHomeChrome.TerminalAction.OpenCatalog,
            SessionListHomeChrome.terminalAction(projects = listOf(mobile)),
        )
    }

    @Test
    fun terminalAction_hiddenWhenTerminalIsUnsupported() {
        assertEquals(
            SessionListHomeChrome.TerminalAction.None,
            SessionListHomeChrome.terminalAction(
                projects = listOf(mobile),
                showsTerminal = false,
            ),
        )
    }

    @Test
    fun terminalAction_twoProjectsOpensCatalog() {
        assertEquals(
            SessionListHomeChrome.TerminalAction.OpenCatalog,
            SessionListHomeChrome.terminalAction(projects = listOf(mobile, playground)),
        )
    }

    @Test
    fun newSessionLongPressAction_oneProjectDoesNothing() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.None,
            SessionListHomeChrome.newSessionLongPressAction(projects = listOf(mobile)),
        )
    }

    @Test
    fun newSessionLongPressAction_twoProjectsPresentsPicker() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentPicker,
            SessionListHomeChrome.newSessionLongPressAction(projects = listOf(mobile, playground)),
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
