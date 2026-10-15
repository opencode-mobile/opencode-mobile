import Testing
@testable import OpenCode_Mobile

struct ServerProjectSuggestionsTests {
    @Test func excludesTheGlobalProjectAndRoot() {
        let projects = [
            project("global", "/", activity: 9),
            project("root", "/", activity: 8),
            project("app", "/Users/me/app", activity: 1),
        ]
        #expect(worktrees(projects) == ["/Users/me/app"])
    }

    @Test func excludesProjectsAlreadyOnHome() {
        let projects = [
            project("app", "/Users/me/app", activity: 2),
            project("web", "/Users/me/web", activity: 1),
        ]
        #expect(worktrees(projects, added: ["/Users/me/app/"]) == ["/Users/me/web"])
    }

    @Test func dedupesByWorktreeKeepingTheNewest() {
        let projects = [
            project("old", "/Users/me/app", activity: 1),
            project("new", "/Users/me/app", activity: 5),
            project("undated", "/Users/me/app", activity: nil),
        ]
        let suggestions = ServerProjectSuggestions.suggestions(from: projects, excluding: [])
        #expect(suggestions.map(\.id) == ["new"])
    }

    @Test func sortsNewestFirstWithMissingTimesLast() {
        let projects = [
            project("undated-a", "/a", activity: nil),
            project("older", "/b", activity: 10),
            project("undated-b", "/c", activity: nil),
            project("newer", "/d", activity: 20),
        ]
        let suggestions = ServerProjectSuggestions.suggestions(from: projects, excluding: [])
        #expect(suggestions.map(\.id) == ["newer", "older", "undated-a", "undated-b"])
    }

    @Test func capsAtFiveNewest() {
        let projects = (1 ... 7).map { index in
            project("p\(index)", "/p\(index)", activity: Int64(index))
        }
        let suggestions = ServerProjectSuggestions.suggestions(from: projects, excluding: [])
        #expect(ServerProjectSuggestions.defaultLimit == 5)
        #expect(suggestions.map(\.id) == ["p7", "p6", "p5", "p4", "p3"])
    }

    private func project(_ id: String, _ worktree: String, activity: Int64?) -> Project {
        Project(
            id: id,
            worktree: worktree,
            displayName: ProjectDomainAdapter.displayName(from: worktree),
            activityAtEpochMs: activity
        )
    }

    private func worktrees(_ projects: [Project], added: [String] = []) -> [String] {
        ServerProjectSuggestions.suggestions(from: projects, excluding: added).map(\.worktree)
    }
}
