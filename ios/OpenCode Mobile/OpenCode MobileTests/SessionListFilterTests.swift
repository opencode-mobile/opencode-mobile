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
}
