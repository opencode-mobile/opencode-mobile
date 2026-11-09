package dev.opencode.mobile.ui.main

import dev.opencode.mobile.domain.home.HomeProjectPickerState
import dev.opencode.mobile.domain.home.HomeScope
import dev.opencode.mobile.domain.home.HomeWorktree
import dev.opencode.mobile.domain.session.NewSessionDraftStart
import dev.opencode.mobile.domain.session.NewSessionPlace
import dev.opencode.mobile.domain.text.UiText
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListProjectPickerRow
import dev.opencode.mobile.ui.components.SessionListScopePaging
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.components.reorderedProjectPickerProjectIds
import dev.opencode.mobile.ui.text.EnglishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListHomeChromeTest {
    @Test
    fun filterTabs_zeroProjectsOmitsAllAndScopeControl() {
        val tabs = SessionListHomeChrome.filterTabs(projects = emptyList(), text = EnglishStrings)
        assertEquals(emptyList<SessionListFilterTab>(), tabs)
    }

    @Test
    fun filterTabs_oneProjectShowsOnlyThatProject() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), text = EnglishStrings)
        assertEquals(
            listOf(SessionListFilterTab.project(worktree = "/repo", title = "opencode-mobile", hasSessions = true)),
            tabs,
        )
    }

    @Test
    fun filterTabs_twoProjectsPrependsAll() {
        val tabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                text = EnglishStrings,
            )
        assertEquals(
            listOf(
                SessionListFilterTab.all(projectCount = 2, hasSessions = true).copy(pickerTitleOverride = "All projects"),
                SessionListFilterTab.project(worktree = "/repo", title = "opencode-mobile", hasSessions = true),
                SessionListFilterTab.project(worktree = "/play", title = "playground", hasSessions = true),
            ),
            tabs,
        )
        assertEquals("All", tabs.first().title)
        assertEquals("All projects", tabs.first().pickerTitle)
    }

    @Test
    fun resolvedFilterId_fallsBackToSoleProjectWhenAllIsOmitted() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), text = EnglishStrings)
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
        val oneProjectTabs = SessionListHomeChrome.filterTabs(projects = listOf(mobile), text = EnglishStrings)
        val storedId =
            SessionListHomeChrome.resolvedFilterId(
                selectedId = SessionListFilterTab.ALL_ID,
                tabs = oneProjectTabs,
            )
        val twoProjectTabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(mobile, playground),
                text = EnglishStrings,
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
                text = EnglishStrings,
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
                text = EnglishStrings,
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
            SessionListHomeChrome.newSessionAction(projects = emptyList(), selectedFilterId = SessionListFilterTab.ALL_ID),
        )
    }

    @Test
    fun newSessionAction_scopedProjectStartsTheDraftOnItsMainCheckout() {
        assertEquals(draft("/repo", "/repo"), SessionListHomeChrome.newSessionAction(listOf(mobile), "/repo"))
        assertEquals(draft("/play", "/play"), SessionListHomeChrome.newSessionAction(listOf(mobile, playground), "/play"))
        // Main checkout /repo even though the first added path is the worktree.
        val addedFromWorktree = worktreed.copy(worktree = "/repo-723")
        assertEquals(draft("proj", "/repo"), SessionListHomeChrome.newSessionAction(listOf(addedFromWorktree, playground), "proj"))
    }

    @Test
    fun newSessionAction_worktreeScopeStartsTheDraftInThatWorktree() {
        assertEquals(
            draft("proj", "/repo", NewSessionPlace.Worktree("/repo-723", "feature/723-iphone-duo")),
            SessionListHomeChrome.newSessionAction(listOf(worktreed, playground), HomeScope.worktreeId("proj", "/repo-723")),
        )
        // The main checkout's own scope is Local.
        assertEquals(
            draft("proj", "/repo"),
            SessionListHomeChrome.newSessionAction(listOf(worktreed, playground), HomeScope.worktreeId("proj", "/repo")),
        )
    }

    @Test
    fun newSessionAction_worktreesOffStartsAWorktreeScopeOnItsProjectsMainCheckout() {
        assertEquals(
            draft("proj", "/repo"),
            SessionListHomeChrome.newSessionAction(
                projects = listOf(worktreed, playground),
                selectedFilterId = HomeScope.worktreeId("proj", "/repo-723"),
                worktreesEnabled = false,
            ),
        )
    }

    @Test
    fun newSessionAction_allScopeStartsWhereTheMostRecentSessionRuns() {
        val sessions =
            listOf(
                session("old", worktree = "/play", directory = "/play", updatedAt = 1_000),
                session("new", worktree = "proj", directory = "/repo-723", updatedAt = 5_000),
                session("mid", worktree = "proj", directory = "/repo", updatedAt = 3_000),
            )
        assertEquals(
            draft("proj", "/repo", NewSessionPlace.Worktree("/repo-723", "feature/723-iphone-duo")),
            SessionListHomeChrome.newSessionAction(listOf(playground, worktreed), "all", sessions = sessions),
        )
        // Worktrees off: the same project, on its main checkout.
        assertEquals(
            draft("proj", "/repo"),
            SessionListHomeChrome.newSessionAction(listOf(playground, worktreed), "all", worktreesEnabled = false, sessions = sessions),
        )
        // The newest in another project.
        assertEquals(
            draft("/play", "/play"),
            SessionListHomeChrome.newSessionAction(
                listOf(worktreed, playground),
                "all",
                sessions = listOf(session("p", worktree = "/play", directory = "/play", updatedAt = 9_000)) + sessions,
            ),
        )
    }

    @Test
    fun newSessionAction_allScopeWithoutSessionsStartsOnTheFirstProject() {
        assertEquals(draft("proj", "/repo"), SessionListHomeChrome.newSessionAction(listOf(worktreed, playground), "all"))
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
    fun filterTabs_listProjectsOnlyAndKeepWorktreesForTheirOwnLookup() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), text = EnglishStrings)

        assertEquals(listOf("all", "proj", "/play"), tabs.map { it.id })
        val project = tabs[1]
        assertEquals("opencode-mobile", project.pickerTitle)
        assertEquals("/repo", project.subtitle)
        assertEquals("All 2 worktrees", project.dockSubtitle)
        assertEquals(null, project.parentId)
        assertEquals("2 projects", tabs.first().subtitle)

        val worktreeTabs = SessionListHomeChrome.worktreeTabs(worktreed, text = EnglishStrings)
        assertEquals(listOf(HomeScope.worktreeId("proj", "/repo"), HomeScope.worktreeId("proj", "/repo-723")), worktreeTabs.map { it.id })
        val main = worktreeTabs[0]
        assertEquals("dev", main.pickerTitle)
        assertEquals("main checkout", main.subtitle)
        assertEquals("proj", main.parentId)
        assertEquals("opencode-mobile", main.dockTitle)
        assertEquals("dev", main.dockSubtitle)
        assertTrue(main.hasSessions)
        val other = worktreeTabs[1]
        assertEquals("feature/723-iphone-duo", other.pickerTitle)
        assertEquals("repo-723", other.subtitle)
        assertEquals("feature/723-iphone-duo · repo-723", other.dockSubtitle)
        assertEquals(project.letter, other.letter)
        assertEquals(project.letterAccent, other.letterAccent)
    }

    @Test
    fun resolvedFilterId_acceptsAWorktreeScopeOnlyWhileItsTabExists() {
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(worktreed, playground), text = EnglishStrings)
        val worktreeId = HomeScope.worktreeId("proj", "/repo-723")

        assertEquals(
            worktreeId,
            SessionListHomeChrome.resolvedFilterId(worktreeId, tabs, SessionListHomeChrome.worktreeTabs(listOf(worktreed, playground), text = EnglishStrings)),
        )
        assertEquals(
            SessionListFilterTab.ALL_ID,
            SessionListHomeChrome.resolvedFilterId(
                worktreeId,
                tabs,
                SessionListHomeChrome.worktreeTabs(listOf(worktreed, playground), worktreesEnabled = false, text = EnglishStrings),
            ),
        )
    }

    @Test
    fun filterTabs_singleWorktreeProjectKeepsItsPath() {
        val single = worktreed.copy(worktrees = worktreed.worktrees.take(1))
        val tabs = SessionListHomeChrome.filterTabs(projects = listOf(single), text = EnglishStrings)

        assertEquals(listOf("proj"), tabs.map { it.id })
        assertEquals("/repo", tabs.single().subtitle)
        assertEquals(null, tabs.single().dockSubtitle)
        assertEquals(emptyList<SessionListFilterTab>(), SessionListHomeChrome.worktreeTabs(single, text = EnglishStrings))
    }

    @Test
    fun filterTabs_worktreesOffShowsAProjectLikeASingleCheckout() {
        val tabs =
            SessionListHomeChrome.filterTabs(
                projects = listOf(worktreed, playground),
                worktreesEnabled = false,
                text = EnglishStrings,
            )

        assertEquals(listOf("all", "proj", "/play"), tabs.map { it.id })
        assertEquals(null, tabs[1].dockSubtitle)
        assertEquals("/repo", tabs[1].subtitle)
        assertEquals(emptyList<SessionListFilterTab>(), SessionListHomeChrome.worktreeTabs(worktreed, worktreesEnabled = false, text = EnglishStrings))
    }

    @Test
    fun pickerRows_collapsedByDefaultWithAPillOnTheMultiWorktreeProject() {
        val rows = SessionListHomeChrome.pickerRows(listOf(worktreed, playground), state = HomeProjectPickerState(), text = EnglishStrings)

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
                state = HomeProjectPickerState().toggleExpanded("proj"),
                text = EnglishStrings,
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
        val collapsed =
            SessionListHomeChrome.pickerRows(
                listOf(many, playground),
                HomeProjectPickerState().toggleExpanded("big"),
                text = EnglishStrings,
            )
        assertEquals(5, collapsed.count { it is SessionListProjectPickerRow.Worktree })
        assertEquals(
            "Show all 7",
            (collapsed.single { it is SessionListProjectPickerRow.ShowAll } as SessionListProjectPickerRow.ShowAll).title,
        )

        val all =
            SessionListHomeChrome.pickerRows(
                listOf(many, playground),
                HomeProjectPickerState().toggleExpanded("big").toggleShowAll("big"),
                text = EnglishStrings,
            )
        assertEquals(7, all.count { it is SessionListProjectPickerRow.Worktree })
        assertEquals("Show fewer", (all.single { it is SessionListProjectPickerRow.ShowAll } as SessionListProjectPickerRow.ShowAll).title)
        // The Show all row sits right after the last worktree, before the next project.
        assertEquals("/play", all.last().key)
    }

    @Test
    fun pickerRows_editingCollapsesAddsTheNoteAndKeepsDisabledPills() {
        val state = HomeProjectPickerState().toggleExpanded("proj").setEditing(true)
        val rows = SessionListHomeChrome.pickerRows(listOf(worktreed, playground), state, text = EnglishStrings)

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
                HomeProjectPickerState().toggleExpanded("proj"),
                worktreesEnabled = false,
                text = EnglishStrings,
            )

        assertEquals(listOf("all", "proj", "/play"), rows.map { it.key })
        assertFalse((rows[1] as SessionListProjectPickerRow.Scope).showsWorktreesPill)
    }

    @Test
    fun dockSwipe_walksAllAndProjectsAndAWorktreeSitsInsideItsProject() {
        val projects = listOf(worktreed, playground)
        val tabs = SessionListHomeChrome.filterTabs(projects, text = EnglishStrings)
        val worktree = SessionListHomeChrome.worktreeTabs(worktreed, text = EnglishStrings)[1]

        fun neighbor(
            selected: SessionListFilterTab,
            direction: SessionListScopePaging.Direction,
        ) = SessionListScopePaging.neighborIndex(tabs, selected, direction)?.let { tabs[it].id }

        assertEquals("/play", neighbor(tabs[1], SessionListScopePaging.Direction.Next))
        assertEquals("proj", neighbor(worktree, SessionListScopePaging.Direction.Previous))
        assertEquals("/play", neighbor(worktree, SessionListScopePaging.Direction.Next))
        assertEquals(null, neighbor(tabs[2], SessionListScopePaging.Direction.Next))
        val lastProjectWorktree =
            SessionListHomeChrome.worktreeTabs(listOf(playground, worktreed), text = EnglishStrings).first()
        val reversed = SessionListHomeChrome.filterTabs(listOf(playground, worktreed), text = EnglishStrings)
        assertEquals(null, SessionListScopePaging.neighborIndex(reversed, lastProjectWorktree, SessionListScopePaging.Direction.Next))
    }

    private fun manyWorktrees(count: Int) =
        SessionListHomeChrome.Project(
            worktree = "/big",
            title = "big",
            hasSessions = false,
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

    private fun draft(
        key: String,
        directory: String,
        place: NewSessionPlace = NewSessionPlace.Local,
    ) = SessionListHomeChrome.NewSessionAction.OpenDraft(NewSessionDraftStart(key, directory, place))

    private fun session(
        id: String,
        worktree: String,
        directory: String,
        updatedAt: Long,
    ) = SessionListSession(
        id = id,
        title = id,
        worktree = worktree,
        letter = 'S',
        letterAccent = LetterAccent.Violet,
        subtitle = directory,
        status = SessionListStatus.Idle,
        caption = UiText.verbatim(""),
        updatedAtEpochMs = updatedAt,
        directory = directory,
    )

    private val worktreed =
        SessionListHomeChrome.Project(
            worktree = "/repo",
            title = "opencode-mobile",
            hasSessions = true,
            id = "proj",
            worktrees =
                listOf(
                    SessionListHomeChrome.Worktree(
                        HomeWorktree("/repo", branch = "dev", isMain = true),
                        hasSessions = true,
                        scopeId = HomeScope.worktreeId("proj", "/repo"),
                    ),
                    SessionListHomeChrome.Worktree(
                        HomeWorktree("/repo-723", branch = "feature/723-iphone-duo"),
                        hasSessions = true,
                        runningCount = 1,
                        scopeId = HomeScope.worktreeId("proj", "/repo-723"),
                    ),
                ),
        )

    private val mobile =
        SessionListHomeChrome.Project(
            worktree = "/repo",
            title = "opencode-mobile",
            hasSessions = true,
        )
    private val playground =
        SessionListHomeChrome.Project(
            worktree = "/play",
            title = "playground",
            hasSessions = true,
        )
}
