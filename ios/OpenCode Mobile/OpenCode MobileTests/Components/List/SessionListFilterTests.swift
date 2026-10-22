import Testing
@testable import OpenCode_Mobile

struct SessionListFilterTests {
    private let sessions: [SessionListSession] = [
        SessionListSession(
            id: "1",
            title: "One",
            worktree: "/a",
            letter: "A",
            letterAccent: .violet,
            subtitle: "/a",
            status: .idle,
            caption: ""
        ),
        SessionListSession(
            id: "2",
            title: "Two",
            worktree: "/b",
            letter: "B",
            letterAccent: .teal,
            subtitle: "/b",
            status: .running,
            caption: ""
        ),
        SessionListSession(
            id: "3",
            title: "Three",
            worktree: "/a",
            letter: "A",
            letterAccent: .violet,
            subtitle: "/a",
            status: .idle,
            caption: ""
        ),
    ]

    @Test func visibleForFilter_allReturnsEverySession() {
        let visible = sessions.visibleForFilter(SessionListFilterTab.allId)
        #expect(visible.map(\.id) == ["1", "2", "3"])
    }

    @Test func visibleForFilter_worktreeReturnsMatchingSessionsOnly() {
        let visible = sessions.visibleForFilter("/a")
        #expect(visible.map(\.id) == ["1", "3"])
    }

    @Test func visibleForFilter_unknownWorktreeReturnsEmpty() {
        let visible = sessions.visibleForFilter("/missing")
        #expect(visible.isEmpty)
    }

    @Test func worktreeFilterClear_emptyWorktreeOffersTheProjectWhenItHasSessions() {
        let clear = worktree().worktreeFilterClear(broaderTabs: broader(projectCount: 4, allCount: 6), visibleIsEmpty: true)

        #expect(clear == WorktreeFilterClear(filterId: "proj", message: SessionListWorktreeEmptyCopy.otherWorktrees))
    }

    @Test func worktreeFilterClear_emptyWorktreeOffersAllWhenOnlyOtherProjectsHaveSessions() {
        let clear = worktree().worktreeFilterClear(broaderTabs: broader(projectCount: 0, allCount: 3), visibleIsEmpty: true)

        #expect(clear == WorktreeFilterClear(filterId: SessionListFilterTab.allId, message: SessionListWorktreeEmptyCopy.otherProjects))
    }

    @Test func worktreeFilterClear_emptyWorktreeStillClearsWhenNothingElseHasSessions() {
        let clear = worktree().worktreeFilterClear(broaderTabs: broader(projectCount: 0, allCount: 0), visibleIsEmpty: true)

        #expect(clear == WorktreeFilterClear(filterId: "proj", message: SessionListWorktreeEmptyCopy.everyWorktree))
    }

    @Test func worktreeFilterClear_skipsWhileTheProjectIsLoadingOrTheWorktreeHasRows() {
        let loading = broader(projectCount: 0, allCount: 0, projectLoad: .loading(isSlow: false))
        #expect(worktree().worktreeFilterClear(broaderTabs: loading, visibleIsEmpty: true) == nil)
        #expect(worktree().worktreeFilterClear(broaderTabs: broader(projectCount: 2, allCount: 2), visibleIsEmpty: false) == nil)
        #expect(
            SessionListFilterTab.project(worktree: "proj", title: "repo", count: 0)
                .worktreeFilterClear(broaderTabs: broader(projectCount: 0, allCount: 2), visibleIsEmpty: true) == nil
        )
    }

    private func worktree() -> SessionListFilterTab {
        SessionListFilterTab(
            id: "worktree:proj\u{1F}/repo-wt",
            title: "feature/x",
            count: 0,
            isWorktree: true,
            parentId: "proj"
        )
    }

    private func broader(projectCount: Int, allCount: Int, projectLoad: SessionListScopeLoad? = nil) -> [SessionListFilterTab] {
        [
            .all(projectCount: 2, sessionCount: allCount),
            SessionListFilterTab(id: "proj", title: "repo", count: projectCount, load: projectLoad),
        ]
    }
}
