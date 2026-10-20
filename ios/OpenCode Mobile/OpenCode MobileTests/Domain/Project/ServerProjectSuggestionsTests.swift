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

    @Test func excludesFoldersWithoutVersionControl() {
        let projects = [
            project("folder", "/Users/me/notes", activity: 2, repository: false),
            project("repo", "/Users/me/app", activity: 1),
        ]
        #expect(worktrees(projects) == ["/Users/me/app"])
    }

    @Test func excludesArchivedProjects() {
        let projects = [
            project("gone", "/Users/me/gone", activity: 3, archived: true),
            project("app", "/Users/me/app", activity: 2),
            project("web", "/Users/me/web", activity: 1, archived: false),
        ]
        #expect(worktrees(projects) == ["/Users/me/app", "/Users/me/web"])
    }

    @Test func archivedDuplicateDoesNotHideTheLiveWorktree() {
        let projects = [
            project("gone", "/Users/me/app", activity: 5, archived: true),
            project("live", "/Users/me/app", activity: 1),
        ]
        let suggestions = ServerProjectSuggestions.suggestions(from: projects, excluding: [])
        #expect(suggestions.map(\.id) == ["live"])
    }

    @Test func excludesTempAndHiddenFolders() {
        let projects = [
            project("upload", "/private/var/folders/_p/x/T/opencode/uploads/8d82", activity: 9),
            project("var", "/var/folders/_p/x/T/repo", activity: 8),
            project("tmp", "/tmp/repo", activity: 7),
            project("var-tmp", "/private/var/tmp/repo", activity: 7),
            project("scratch", "/Users/me/.t3/scratch", activity: 6),
            project("dot", "/Users/me/.jenv", activity: 5),
            project("win-temp", #"C:\Users\me\AppData\Local\Temp\repo"#, activity: 4),
            project("tmpish", "/Users/me/tmp-tools", activity: 3),
            project("app", "/Users/me/app", activity: 2),
        ]
        #expect(worktrees(projects) == ["/Users/me/tmp-tools", "/Users/me/app"])
    }

    /// A real server's list: agents and tools left plain-folder projects that outrank the repos.
    @Test func suggestsReposNotOnHomeFromAgentCreatedServerList() {
        let projects = [
            project("opencode", "/Users/ivan/Projects/opencode", activity: 20),
            project("mobile", "/Users/ivan/Projects/opencode-mobile", activity: 19),
            project("home", "/Users/ivan", activity: 18, repository: false),
            project("t3code", "/Users/ivan/Projects/t3code", activity: 17),
            project("root", "/", activity: 16, repository: false),
            project("scratch", "/Users/ivan/.t3/scratch", activity: 15, repository: false),
            project("projects", "/Users/ivan/Projects", activity: 14, repository: false),
            project("evidence", "/Users/ivan/Projects/oc-655-evidence", activity: 13, repository: false),
            project("upload", "/private/var/folders/_p/drt8/T/opencode/uploads/8d82a3f4", activity: 12, repository: false),
            project("global", "/", activity: 11, repository: false),
            project("instax", "/Users/ivan/Projects/INSTAX", activity: 10),
            project("homelab", "/Users/ivan/Projects/homelab", activity: 9),
            project("difftest", "/Users/ivan/Projects/DiffTest", activity: 8),
            project("docs", "/Users/ivan/Projects/opencode-mobile-docs", activity: 7),
            project("jenv", "/Users/ivan/.jenv", activity: 6),
        ]
        let home = [
            "/Users/ivan/Projects/opencode",
            "/Users/ivan/Projects/opencode-mobile",
            "/Users/ivan",
            "/Users/ivan/Projects/t3code",
        ]
        let suggestions = ServerProjectSuggestions.suggestions(from: projects, excluding: home)
        #expect(suggestions.map(\.displayName) == ["INSTAX", "homelab", "DiffTest", "opencode-mobile-docs"])
    }

    private func project(
        _ id: String,
        _ worktree: String,
        activity: Int64?,
        repository: Bool = true,
        archived: Bool = false
    ) -> Project {
        Project(
            id: id,
            worktree: worktree,
            displayName: ProjectDomainAdapter.displayName(from: worktree),
            activityAtEpochMs: activity,
            isRepository: repository,
            isArchived: archived
        )
    }

    private func worktrees(_ projects: [Project], added: [String] = []) -> [String] {
        ServerProjectSuggestions.suggestions(from: projects, excluding: added).map(\.worktree)
    }
}
