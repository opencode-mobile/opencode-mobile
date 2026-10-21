import Testing
@testable import OpenCode_Mobile

struct HomeProjectPickerStateTests {
    @Test func toggleExpandedKeepsOneProjectOpen() {
        var state = HomeProjectPickerState()
        state.toggleExpanded("a")
        #expect(state.expandedProjectKey == "a")
        state.toggleExpanded("b")
        #expect(state.expandedProjectKey == "b")
        #expect(!state.isExpanded("a"))
        state.toggleExpanded("b")
        #expect(state.expandedProjectKey == nil)
    }

    @Test func toggleShowAllFlipsPerProject() {
        var state = HomeProjectPickerState()
        state.toggleShowAll("a")
        #expect(state.showsAll("a"))
        #expect(!state.showsAll("b"))
        state.toggleShowAll("a")
        #expect(!state.showsAll("a"))
    }

    @Test func editingCollapsesEverythingAndStaysCollapsedAfter() {
        var state = HomeProjectPickerState(expandedProjectKey: "a", showAllProjectKeys: ["a"])
        state.setEditing(true)
        #expect(state.isEditing)
        #expect(state.expandedProjectKey == nil)
        #expect(state.showAllProjectKeys.isEmpty)
        // Pills are inert while editing.
        state.toggleExpanded("a")
        #expect(state.expandedProjectKey == nil)
        state.setEditing(false)
        #expect(!state.isEditing)
        #expect(state.expandedProjectKey == nil)
    }

    @Test func initialExpandsTheSelectedWorktreesProject() {
        let projects = [project(worktreeCount: 3), other]
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/wt-2")
        let state = HomeProjectPickerState.initial(selectedScopeId: scope, projects: projects)
        #expect(state.expandedProjectKey == "prj")
        #expect(state.showAllProjectKeys.isEmpty)
    }

    @Test func initialShowsAllWhenTheSelectedWorktreeIsPastTheFirstFive() {
        let big = project(worktreeCount: 8)
        let sixth = big.worktrees[5].directory
        let state = HomeProjectPickerState.initial(
            selectedScopeId: HomeScope.worktreeID(projectKey: "prj", directory: sixth),
            projects: [big]
        )
        #expect(state.expandedProjectKey == "prj")
        #expect(state.showsAll("prj"))
        let fifth = big.worktrees[4].directory
        let early = HomeProjectPickerState.initial(
            selectedScopeId: HomeScope.worktreeID(projectKey: "prj", directory: fifth),
            projects: [big]
        )
        #expect(!early.showsAll("prj"))
    }

    @Test func initialStartsCollapsedOnAProjectOrAllOrWithWorktreesOff() {
        let projects = [project(worktreeCount: 3), other]
        #expect(HomeProjectPickerState.initial(selectedScopeId: "prj", projects: projects) == HomeProjectPickerState())
        #expect(
            HomeProjectPickerState.initial(selectedScopeId: SessionListFilterTab.allId, projects: projects)
                == HomeProjectPickerState()
        )
        let scope = HomeScope.worktreeID(projectKey: "prj", directory: "/src/wt-2")
        #expect(
            HomeProjectPickerState.initial(selectedScopeId: scope, projects: projects, worktreesEnabled: false)
                == HomeProjectPickerState()
        )
    }
}

struct HomeProjectPickerRowTests {
    @Test func collapsedPickerListsProjectsWithAPill() {
        let projects = [project(worktreeCount: 3), other]
        let rows = rows(projects, state: HomeProjectPickerState())
        #expect(rows.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/notes"])
        guard case let .project(_, pill?) = rows[1] else {
            Issue.record("expected a worktree pill")
            return
        }
        #expect(pill.title == "3 worktrees")
        #expect(!pill.isExpanded)
        #expect(pill.enabled)
        #expect(pill.accessibilityLabel == "Show worktrees of repo")
        guard case .project(_, nil) = rows[2] else {
            Issue.record("a single-worktree project has no pill")
            return
        }
    }

    @Test func expandedProjectListsItsWorktreesUnderIt() {
        let projects = [project(worktreeCount: 3), other]
        let rows = rows(projects, state: HomeProjectPickerState(expandedProjectKey: "prj"))
        #expect(rows.map(\.id) == [
            SessionListFilterTab.allId,
            "prj",
            HomeScope.worktreeID(projectKey: "prj", directory: "/src/repo"),
            HomeScope.worktreeID(projectKey: "prj", directory: "/src/wt-1"),
            HomeScope.worktreeID(projectKey: "prj", directory: "/src/wt-2"),
            "path:/notes",
        ])
        guard case let .project(_, pill?) = rows[1] else {
            Issue.record("expected a worktree pill")
            return
        }
        #expect(pill.isExpanded)
        #expect(pill.accessibilityLabel == "Hide worktrees of repo")
    }

    @Test func moreThanFiveWorktreesShowFiveAndShowAll() {
        let big = project(worktreeCount: 8)
        let collapsed = rows([big, other], state: HomeProjectPickerState(expandedProjectKey: "prj"))
        let worktreeRows = collapsed.filter(isWorktreeRow)
        #expect(worktreeRows.count == 5)
        #expect(collapsed.contains(.showAll(projectKey: "prj", title: "Show all 8")))

        let expanded = rows(
            [big, other],
            state: HomeProjectPickerState(expandedProjectKey: "prj", showAllProjectKeys: ["prj"])
        )
        #expect(expanded.filter(isWorktreeRow).count == 8)
        #expect(expanded.contains(.showAll(projectKey: "prj", title: "Show fewer")))
        // Show all / Show fewer sits right after the project's worktrees.
        #expect(expanded.last?.id == "path:/notes")
    }

    @Test func fiveOrFewerWorktreesHaveNoShowAllRow() {
        let five = project(worktreeCount: 5)
        let rows = rows([five, other], state: HomeProjectPickerState(expandedProjectKey: "prj"))
        #expect(!rows.contains(where: isShowAllRow))
    }

    @Test func editingShowsTheNoteCollapsedRowsAndDisabledPills() {
        var state = HomeProjectPickerState(expandedProjectKey: "prj")
        state.setEditing(true)
        let rows = rows([project(worktreeCount: 3), other], state: state)
        #expect(rows.map(\.id) == [HomeProjectPickerRow.noteID, SessionListFilterTab.allId, "prj", "path:/notes"])
        guard case let .project(_, pill?) = rows[2] else {
            Issue.record("pills stay visible while editing")
            return
        }
        #expect(!pill.enabled)
        #expect(!pill.isExpanded)
        #expect(HomeProjectCopy.reorderNote == "Drag to reorder. Worktrees stay with their project.")
    }

    @Test func reorderKeepsProjectsOnly() {
        var state = HomeProjectPickerState()
        state.setEditing(true)
        let rows = rows([project(worktreeCount: 3), other], state: state)
        // Drag the last project above the first.
        #expect(HomeProjectPickerRow.reorderedProjectKeys(rows, from: 3, to: 2) == ["path:/notes", "prj"])
    }

    @Test func worktreesOffListsProjectsWithoutPills() {
        let projects = [project(worktreeCount: 3), other]
        let rows = HomeProjectPickerRow.rows(
            tabs: SessionListHomeChrome.filterTabs(projects: projects, sessions: [], worktreesEnabled: false),
            projects: projects,
            sessions: [],
            state: HomeProjectPickerState(expandedProjectKey: "prj"),
            worktreesEnabled: false
        )
        #expect(rows.map(\.id) == [SessionListFilterTab.allId, "prj", "path:/notes"])
        #expect(rows.allSatisfy { row in
            if case let .project(_, pill) = row { return pill == nil }
            return true
        })
    }

    private func rows(_ projects: [HomeProject], state: HomeProjectPickerState) -> [HomeProjectPickerRow] {
        HomeProjectPickerRow.rows(
            tabs: SessionListHomeChrome.filterTabs(projects: projects, sessions: []),
            projects: projects,
            sessions: [],
            state: state
        )
    }
}

private func project(worktreeCount: Int) -> HomeProject {
    let resolution = HomeProjectResolution(id: "prj", canonical: "/src/repo", name: "repo", isRepository: true)
    let extra = (1 ..< worktreeCount).map { "/src/wt-\($0)" }
    return HomeProjectGrouping.group(
        addedPaths: ["/src/repo"],
        resolutions: ["/src/repo": resolution],
        inventories: ["/src/repo": ["/src/repo"] + extra]
    )[0]
}

private let other = HomeProject(
    key: "path:/notes",
    projectID: nil,
    displayName: "notes",
    memberPaths: ["/notes"],
    isRepository: false,
    worktrees: [HomeWorktree(directory: "/notes", branch: nil, isMain: true)]
)

private func isWorktreeRow(_ row: HomeProjectPickerRow) -> Bool {
    if case .worktree = row { return true }
    return false
}

private func isShowAllRow(_ row: HomeProjectPickerRow) -> Bool {
    if case .showAll = row { return true }
    return false
}
