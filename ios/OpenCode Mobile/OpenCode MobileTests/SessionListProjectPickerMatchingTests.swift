import Testing
@testable import OpenCode_Mobile

struct SessionListProjectPickerMatchingTests {
    @Test func emptyQueryReturnsAllTabs() {
        #expect(SessionListProjectPickerMatching.tabs(sampleTabs, query: "  ") == sampleTabs)
    }

    @Test func matchesTitle() {
        let matches = SessionListProjectPickerMatching.tabs(sampleTabs, query: "play")
        #expect(matches.map(\.id) == ["/play"])
    }

    @Test func matchesWorktreePath() {
        let matches = SessionListProjectPickerMatching.tabs(sampleTabs, query: "/repo")
        #expect(matches.map(\.id) == ["/repo"])
    }

    @Test func matchesAllProjectsLabel() {
        let matches = SessionListProjectPickerMatching.tabs(sampleTabs, query: "all")
        #expect(matches.map(\.id) == [SessionListFilterTab.allId])
    }

    @Test func noMatchesReturnsEmpty() {
        #expect(SessionListProjectPickerMatching.tabs(sampleTabs, query: "zzz").isEmpty)
    }

    private let sampleTabs: [SessionListFilterTab] = [
        .all(projectCount: 2, sessionCount: 3),
        .project(worktree: "/repo", title: "opencode-mobile", count: 2),
        .project(worktree: "/play", title: "playground", count: 1),
    ]
}
