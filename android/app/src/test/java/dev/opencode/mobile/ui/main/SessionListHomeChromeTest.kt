package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
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

    @Test
    fun filterTabs_projectWithWorktreesListsThemIndentedUnderIt() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), sessionCount = 4)

        assertEquals(
            listOf("all", "proj", HomeScope.worktreeId("proj", "/repo"), HomeScope.worktreeId("proj", "/repo-723"), "/play"),
            tabs.map { it.id },
        )
        val project = tabs[1]
        assertEquals("opencode-mobile", project.pickerTitle)
        assertEquals("2 worktrees", project.subtitle)
        assertEquals("All 2 worktrees", project.dockSubtitle)
        assertEquals(null, project.parentId)
        val main = tabs[2]
        assertEquals("dev", main.pickerTitle)
        assertEquals("main checkout", main.subtitle)
        assertEquals("proj", main.parentId)
        assertEquals("opencode-mobile", main.dockTitle)
        assertEquals("dev", main.dockSubtitle)
        assertEquals(1, main.count)
        val other = tabs[3]
        assertEquals("feature/723-iphone-duo", other.pickerTitle)
        assertEquals("repo-723", other.subtitle)
        assertEquals("feature/723-iphone-duo · repo-723", other.dockSubtitle)
        assertEquals(project.letter, other.letter)
        assertEquals(project.letterAccent, other.letterAccent)
        assertEquals("2 projects", tabs.first().subtitle)
    }

    @Test
    fun filterTabs_singleWorktreeProjectKeepsItsPath() {
        val single = worktreed.copy(worktrees = worktreed.worktrees.take(1))
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(single), sessionCount = 2)

        assertEquals(listOf("proj"), tabs.map { it.id })
        assertEquals("/repo", tabs.single().subtitle)
        assertEquals(null, tabs.single().dockSubtitle)
    }

    @Test
    fun newSessionAction_worktreeScopeCreatesInThatWorktree() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo-723"),
            SessionListHomeChrome.newSessionAction(
                projects = listOf(worktreed, playground),
                selectedFilterId = HomeScope.worktreeId("proj", "/repo-723"),
            ),
        )
    }

    @Test
    fun newSessionAction_projectWithWorktreesAsksWhereToRun() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.ChooseWorktree("proj"),
            SessionListHomeChrome.newSessionAction(projects = listOf(worktreed, playground), selectedFilterId = "proj"),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.ChooseWorktree("proj"),
            SessionListHomeChrome.newSessionAction(projects = listOf(worktreed), selectedFilterId = "all"),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.ChooseWorktree("proj"),
            SessionListHomeChrome.projectAction(worktreed),
        )
        assertEquals(SessionListHomeChrome.NewSessionAction.Create("/play"), SessionListHomeChrome.projectAction(playground))
    }

    private val worktreed =
        SessionListHomeChrome.Project(
            worktree = "/repo",
            title = "opencode-mobile",
            sessionCount = 3,
            id = "proj",
            worktrees =
                listOf(
                    SessionListHomeChrome.Worktree(
                        HomeWorktree("/repo", branch = "dev", isMain = true),
                        sessionCount = 1,
                        scopeId = HomeScope.worktreeId("proj", "/repo"),
                    ),
                    SessionListHomeChrome.Worktree(
                        HomeWorktree("/repo-723", branch = "feature/723-iphone-duo"),
                        sessionCount = 2,
                        runningCount = 1,
                        scopeId = HomeScope.worktreeId("proj", "/repo-723"),
                    ),
                ),
        )

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
