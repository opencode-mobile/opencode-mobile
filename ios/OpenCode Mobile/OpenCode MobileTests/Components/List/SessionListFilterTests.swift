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
        let clear = worktree().worktreeFilterClear(
            broaderTabs: broader(),
            visibleIsEmpty: true,
            scopeHasSessions: has(project: true, all: true)
        )

        #expect(clear == WorktreeFilterClear(filterId: "proj", message: SessionListWorktreeEmptyCopy.otherWorktrees))
    }

    @Test func worktreeFilterClear_emptyWorktreeOffersAllWhenOnlyOtherProjectsHaveSessions() {
        let clear = worktree().worktreeFilterClear(
            broaderTabs: broader(),
            visibleIsEmpty: true,
            scopeHasSessions: has(project: false, all: true)
        )

        #expect(clear == WorktreeFilterClear(filterId: SessionListFilterTab.allId, message: SessionListWorktreeEmptyCopy.otherProjects))
    }

    @Test func worktreeFilterClear_emptyWorktreeStillClearsWhenNothingElseHasSessions() {
        let clear = worktree().worktreeFilterClear(
            broaderTabs: broader(),
            visibleIsEmpty: true,
            scopeHasSessions: has(project: false, all: false)
        )

        #expect(clear == WorktreeFilterClear(filterId: "proj", message: SessionListWorktreeEmptyCopy.everyWorktree))
    }

    @Test func worktreeFilterClear_skipsWhileTheProjectIsLoadingOrTheWorktreeHasRows() {
        let none = has(project: false, all: false)
        let loading = broader(projectLoad: .loading(isSlow: false))
        #expect(worktree().worktreeFilterClear(broaderTabs: loading, visibleIsEmpty: true, scopeHasSessions: none) == nil)
        #expect(
            worktree().worktreeFilterClear(
                broaderTabs: broader(),
                visibleIsEmpty: false,
                scopeHasSessions: has(project: true, all: true)
            ) == nil
        )
        #expect(
            SessionListFilterTab.project(worktree: "proj", title: "repo")
                .worktreeFilterClear(broaderTabs: broader(), visibleIsEmpty: true, scopeHasSessions: has(project: false, all: true)) == nil
        )
    }

    private func worktree() -> SessionListFilterTab {
        SessionListFilterTab(
            id: "worktree:proj\u{1F}/repo-wt",
            title: "feature/x",
            isWorktree: true,
            parentId: "proj"
        )
    }

    private func broader(projectLoad: SessionListScopeLoad? = nil) -> [SessionListFilterTab] {
        [
            .all(projectCount: 2),
            SessionListFilterTab(id: "proj", title: "repo", load: projectLoad),
        ]
    }

    private func has(project: Bool, all: Bool) -> (String) -> Bool {
        { id in id == SessionListFilterTab.allId ? all : id == "proj" ? project : false }
    }
}
