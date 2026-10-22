package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRow
import dev.opencode.mobile.ui.components.SessionListScopePaging
import dev.opencode.mobile.ui.components.reorderedProjectPickerProjectIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun newSessionAction_allSelectedPresentsProjectChoice() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = false)),
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
    fun newSessionLongPressAction_twoProjectsPresentsHoldPicker() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = true)),
            SessionListHomeChrome.newSessionLongPressAction(projects = listOf(mobile, playground)),
        )
    }

    @Test
    fun filterTabs_listProjectsOnlyAndKeepWorktreesForTheirOwnLookup() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), sessionCount = 4)

        assertEquals(listOf("all", "proj", "/play"), tabs.map { it.id })
        val project = tabs[1]
        assertEquals("opencode-mobile", project.pickerTitle)
        assertEquals("/repo", project.subtitle)
        assertEquals("All 2 worktrees", project.dockSubtitle)
        assertEquals(null, project.parentId)
        assertEquals("2 projects", tabs.first().subtitle)

        val worktreeTabs = SessionListHomeChrome.worktreeTabs(worktreed)
        assertEquals(listOf(HomeScope.worktreeId("proj", "/repo"), HomeScope.worktreeId("proj", "/repo-723")), worktreeTabs.map { it.id })
        val main = worktreeTabs[0]
        assertEquals("dev", main.pickerTitle)
        assertEquals("main checkout", main.subtitle)
        assertEquals("proj", main.parentId)
        assertEquals("opencode-mobile", main.dockTitle)
        assertEquals("dev", main.dockSubtitle)
        assertEquals(1, main.count)
        val other = worktreeTabs[1]
        assertEquals("feature/723-iphone-duo", other.pickerTitle)
        assertEquals("repo-723", other.subtitle)
        assertEquals("feature/723-iphone-duo · repo-723", other.dockSubtitle)
        assertEquals(project.letter, other.letter)
        assertEquals(project.letterAccent, other.letterAccent)
    }

    @Test
    fun resolvedFilterId_acceptsAWorktreeScopeOnlyWhileItsTabExists() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), sessionCount = 4)
        val worktreeId = HomeScope.worktreeId("proj", "/repo-723")

        assertEquals(
            worktreeId,
            SessionListHomeChrome.resolvedFilterId(worktreeId, tabs, SessionListHomeChrome.worktreeTabs(listOf(worktreed, playground))),
        )
        assertEquals(
            SessionListFilterTab.ALL_ID,
            SessionListHomeChrome.resolvedFilterId(
                worktreeId,
                tabs,
                SessionListHomeChrome.worktreeTabs(listOf(worktreed, playground), worktreesEnabled = false),
            ),
        )
    }

    @Test
    fun filterTabs_singleWorktreeProjectKeepsItsPath() {
        val single = worktreed.copy(worktrees = worktreed.worktrees.take(1))
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(single), sessionCount = 2)

        assertEquals(listOf("proj"), tabs.map { it.id })
        assertEquals("/repo", tabs.single().subtitle)
        assertEquals(null, tabs.single().dockSubtitle)
        assertEquals(emptyList<SessionListFilterTab>(), SessionListHomeChrome.worktreeTabs(single))
    }

    @Test
    fun filterTabs_worktreesOffShowsAProjectLikeASingleCheckout() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), sessionCount = 4, worktreesEnabled = false)

        assertEquals(listOf("all", "proj", "/play"), tabs.map { it.id })
        assertEquals(null, tabs[1].dockSubtitle)
        assertEquals("/repo", tabs[1].subtitle)
        assertEquals(emptyList<SessionListFilterTab>(), SessionListHomeChrome.worktreeTabs(worktreed, worktreesEnabled = false))
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
    fun newSessionAction_tapNeverAsksAndStartsOnTheMainCheckout() {
        // Main checkout /repo even though the first added path is the worktree.
        val addedFromWorktree = worktreed.copy(worktree = "/repo-723")
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.newSessionAction(projects = listOf(addedFromWorktree, playground), selectedFilterId = "proj"),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.newSessionAction(projects = listOf(addedFromWorktree), selectedFilterId = "all"),
        )
        assertEquals("/repo", addedFromWorktree.mainCheckoutDirectory)
        assertEquals("/play", playground.mainCheckoutDirectory)
    }

    @Test
    fun newSessionAction_worktreesOffStartsAWorktreeScopeOnItsProjectsMainCheckout() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.newSessionAction(
                projects = listOf(worktreed, playground),
                selectedFilterId = HomeScope.worktreeId("proj", "/repo-723"),
                worktreesEnabled = false,
            ),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = false)),
            SessionListHomeChrome.newSessionAction(listOf(worktreed, playground), "all", worktreesEnabled = false),
        )
    }

    @Test
    fun newSessionLongPressAction_opensRunInForTheTargetProjectWithOtherProjects() {
        val runIn =
            SessionListHomeChrome.NewSessionAction.PresentSheet(
                SessionListHomeChrome.NewSessionSheetMode.RunIn("proj", showsOtherProjects = true),
            )
        assertEquals(runIn, SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed, playground), "proj"))
        assertEquals(
            runIn,
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed, playground), HomeScope.worktreeId("proj", "/repo")),
        )
        // All scope with one project: that project; no other projects to offer.
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(
                SessionListHomeChrome.NewSessionSheetMode.RunIn("proj", showsOtherProjects = false),
            ),
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed), "all"),
        )
        // A single-checkout target falls back to the project list.
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = true)),
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed, playground), "/play"),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = true)),
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed, playground), "all"),
        )
    }

    @Test
    fun newSessionLongPressAction_worktreesOffNeverOffersRunIn() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(SessionListHomeChrome.NewSessionSheetMode.Projects(holdPick = true)),
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed, playground), "proj", worktreesEnabled = false),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.None,
            SessionListHomeChrome.newSessionLongPressAction(listOf(worktreed), "proj", worktreesEnabled = false),
        )
    }

    @Test
    fun projectPickAction_holdPickAsksWhereToRunTapPickStartsOnMain() {
        assertEquals(
            SessionListHomeChrome.NewSessionAction.PresentSheet(
                SessionListHomeChrome.NewSessionSheetMode.RunIn("proj", showsOtherProjects = false),
            ),
            SessionListHomeChrome.projectPickAction(worktreed, holdPick = true),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.projectPickAction(worktreed, holdPick = false),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/repo"),
            SessionListHomeChrome.projectPickAction(worktreed, holdPick = true, worktreesEnabled = false),
        )
        assertEquals(
            SessionListHomeChrome.NewSessionAction.Create("/play"),
            SessionListHomeChrome.projectPickAction(playground, holdPick = true),
        )
    }

    @Test
    fun pickerRows_collapsedByDefaultWithAPillOnTheMultiWorktreeProject() {
        val rows = SessionListHomeChrome.pickerRows(listOf(worktreed, playground), sessionCount = 4, state = HomeProjectPickerState())

        assertEquals(listOf("all", "proj", "/play"), rows.map { it.key })
        val project = rows[1] as SessionListProjectPickerRow.Scope
        assertTrue(project.showsWorktreesPill)
        assertEquals(2, project.worktreeCount)
        assertFalse(project.expanded)
        assertFalse((rows[2] as SessionListProjectPickerRow.Scope).showsWorktreesPill)
    }

    @Test
    fun pickerRows_expandedProjectListsItsWorktreesUnderIt() {
        val rows =
            SessionListHomeChrome.pickerRows(
                listOf(worktreed, playground),
                sessionCount = 4,
                state = HomeProjectPickerState().toggleExpanded("proj"),
            )

        assertEquals(
            listOf("all", "proj", HomeScope.worktreeId("proj", "/repo"), HomeScope.worktreeId("proj", "/repo-723"), "/play"),
            rows.map { it.key },
        )
        assertTrue((rows[1] as SessionListProjectPickerRow.Scope).expanded)
        assertTrue(rows[2] is SessionListProjectPickerRow.Worktree)
    }

    @Test
    fun pickerRows_manyWorktreesShowFiveThenShowAllThenShowFewer() {
        val many = manyWorktrees(count = 7)
        val collapsed = SessionListHomeChrome.pickerRows(listOf(many, playground), 0, HomeProjectPickerState().toggleExpanded("big"))
        assertEquals(5, collapsed.count { it is SessionListProjectPickerRow.Worktree })
        assertEquals(
            "Show all 7",
            (collapsed.single { it is SessionListProjectPickerRow.ShowAll } as SessionListProjectPickerRow.ShowAll).title,
        )

        val all =
            SessionListHomeChrome.pickerRows(
                listOf(many, playground),
                0,
                HomeProjectPickerState().toggleExpanded("big").toggleShowAll("big"),
            )
        assertEquals(7, all.count { it is SessionListProjectPickerRow.Worktree })
        assertEquals("Show fewer", (all.single { it is SessionListProjectPickerRow.ShowAll } as SessionListProjectPickerRow.ShowAll).title)
        // The Show all row sits right after the last worktree, before the next project.
        assertEquals("/play", all.last().key)
    }

    @Test
    fun pickerRows_editingCollapsesAddsTheNoteAndKeepsDisabledPills() {
        val state = HomeProjectPickerState().toggleExpanded("proj").setEditing(true)
        val rows = SessionListHomeChrome.pickerRows(listOf(worktreed, playground), 4, state)

        assertEquals(listOf("note", "all", "proj", "/play"), rows.map { it.key })
        assertEquals("Drag to reorder. Worktrees stay with their project.", (rows[0] as SessionListProjectPickerRow.Note).text)
        val project = rows[2] as SessionListProjectPickerRow.Scope
        assertTrue(project.showsWorktreesPill)
        assertFalse(project.expanded)
        // Reorder output names projects only.
        assertEquals(listOf("/play", "proj"), reorderedProjectPickerProjectIds(rows, from = 3, to = 2))
    }

    @Test
    fun pickerRows_worktreesOffListsProjectsOnly() {
        val rows =
            SessionListHomeChrome.pickerRows(
                listOf(worktreed, playground),
                4,
                HomeProjectPickerState().toggleExpanded("proj"),
                worktreesEnabled = false,
            )

        assertEquals(listOf("all", "proj", "/play"), rows.map { it.key })
        assertFalse((rows[1] as SessionListProjectPickerRow.Scope).showsWorktreesPill)
    }

    @Test
    fun dockSwipe_walksAllAndProjectsAndAWorktreeSitsInsideItsProject() {
        val projects = listOf(worktreed, playground)
        val tabs = SessionListHomeChrome.filterTabs(projects, sessionCount = 4)
        val worktree = SessionListHomeChrome.worktreeTabs(worktreed)[1]

        fun neighbor(
            selected: SessionListFilterTab,
            direction: SessionListScopePaging.Direction,
        ) = SessionListScopePaging.neighborIndex(tabs, selected, direction)?.let { tabs[it].id }

        assertEquals("/play", neighbor(tabs[1], SessionListScopePaging.Direction.Next))
        assertEquals("proj", neighbor(worktree, SessionListScopePaging.Direction.Previous))
        assertEquals("/play", neighbor(worktree, SessionListScopePaging.Direction.Next))
        assertEquals(null, neighbor(tabs[2], SessionListScopePaging.Direction.Next))
        val lastProjectWorktree =
            SessionListHomeChrome.worktreeTabs(listOf(playground, worktreed)).first()
        val reversed = SessionListHomeChrome.filterTabs(listOf(playground, worktreed), sessionCount = 4)
        assertEquals(null, SessionListScopePaging.neighborIndex(reversed, lastProjectWorktree, SessionListScopePaging.Direction.Next))
    }

    private fun manyWorktrees(count: Int) =
        SessionListHomeChrome.Project(
            worktree = "/big",
            title = "big",
            sessionCount = 0,
            id = "big",
            worktrees =
                (0 until count).map { index ->
                    val directory = if (index == 0) "/big" else "/big-$index"
                    SessionListHomeChrome.Worktree(
                        HomeWorktree(directory, branch = "b$index", isMain = index == 0),
                        scopeId = HomeScope.worktreeId("big", directory),
                    )
                },
        )

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
