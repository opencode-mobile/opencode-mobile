import Testing
@testable import OpenCode_Mobile

struct SessionListHomeChromeTests {
    @Test func filterTabs_zeroProjectsOmitsAllAndScopeControl() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [], sessionCount: 0)
        #expect(tabs.isEmpty)
    }

    @Test func filterTabs_oneProjectShowsOnlyThatProject() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile], sessionCount: 2)
        #expect(tabs == [
            SessionListFilterTab.project(worktree: "/repo", title: "opencode-mobile", count: 2),
        ])
    }

    @Test func filterTabs_twoProjectsPrependsAll() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground], sessionCount: 3)
        #expect(tabs == [
            SessionListFilterTab.all(projectCount: 2, sessionCount: 3),
            SessionListFilterTab.project(worktree: "/repo", title: "opencode-mobile", count: 2),
            SessionListFilterTab.project(worktree: "/play", title: "playground", count: 1),
        ])
        #expect(tabs.first?.title == "All")
        #expect(tabs.first?.pickerTitle == "All projects")
    }

    @Test func resolvedFilterId_fallsBackToSoleProjectWhenAllIsOmitted() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile], sessionCount: 2)
        #expect(
            SessionListHomeChrome.resolvedFilterId(
                selectedId: SessionListFilterTab.allId,
                tabs: tabs
            ) == "/repo"
        )
    }

    @Test func resolvedFilterId_writeBackThenSecondProjectKeepsSoleProject() {
        let oneProjectTabs = SessionListHomeChrome.filterTabs(projects: [mobile], sessionCount: 2)
        let storedId = SessionListHomeChrome.resolvedFilterId(
            selectedId: SessionListFilterTab.allId,
            tabs: oneProjectTabs
        )
        let twoProjectTabs = SessionListHomeChrome.filterTabs(
            projects: [mobile, playground],
            sessionCount: 3
        )
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: storedId, tabs: twoProjectTabs) == "/repo")
    }

    @Test func resolvedFilterId_keepsProjectWhenAllReappears() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground], sessionCount: 3)
        #expect(SessionListHomeChrome.resolvedFilterId(selectedId: "/repo", tabs: tabs) == "/repo")
    }

    @Test func resolvedFilterId_fallsBackToAllWhenTwoPlusAndSelectionMissing() {
        let tabs = SessionListHomeChrome.filterTabs(projects: [mobile, playground], sessionCount: 3)
        #expect(
            SessionListHomeChrome.resolvedFilterId(selectedId: "/gone", tabs: tabs)
                == SessionListFilterTab.allId
        )
    }

    @Test func newSessionAction_zeroProjectsDoesNothing() {
        #expect(SessionListHomeChrome.newSessionAction(projects: []) == .none)
    }

    @Test func newSessionAction_oneProjectCreatesInThatWorktree() {
        #expect(SessionListHomeChrome.newSessionAction(projects: [mobile]) == .create(worktree: "/repo"))
    }

    @Test func newSessionAction_twoPlusPresentsPicker() {
        #expect(
            SessionListHomeChrome.newSessionAction(projects: [mobile, playground]) == .presentPicker
        )
    }

    private let mobile = SessionListHomeChrome.Project(
        worktree: "/repo",
        title: "opencode-mobile",
        sessionCount: 2
    )
    private let playground = SessionListHomeChrome.Project(
        worktree: "/play",
        title: "playground",
        sessionCount: 1
    )
}
