import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct HomeWorktreeMarksTests {
    private let resolution = HomeProjectResolution(id: "prj", canonical: "/repo", isRepository: true)

    @Test func v1ListsNonAddedWorktreesAndTheMainCheckout() async {
        let listed = ListedDirectories(sessions: ["/repo": ["s-main"], "/wt/a": ["s-a"], "/wt/b": ["s-b"]])
        let marks = makeMarks(load: .loaded(directories: ["/wt/a", "/wt/b"], listsSessions: true), listed: listed)
        marks.setAddedPaths(["/wt/b"])

        let update = await marks.load(addedPath: "/wt/b")

        #expect(update.resolution == resolution)
        #expect(update.directories == ["/wt/a", "/wt/b"])
        #expect(Set(update.extras.map(\.id)) == ["s-main", "s-a"])
        #expect(await listed.calls == ["/repo", "/wt/a"])
    }

    @Test func secondMemberOfAProjectDoesNotListExtrasAgain() async {
        let listed = ListedDirectories(sessions: ["/wt/a": ["s-a"]])
        let marks = makeMarks(load: .loaded(directories: ["/wt/a", "/wt/b"], listsSessions: true), listed: listed)
        marks.setAddedPaths(["/repo", "/wt/b"])

        let first = await marks.load(addedPath: "/repo")
        let second = await marks.load(addedPath: "/wt/b")

        #expect(first.extras.map(\.id) == ["s-a"])
        #expect(second.extras.isEmpty)
        #expect(await listed.calls == ["/wt/a"])
    }

    @Test func v1OldServerStillListsTheMainCheckout() async {
        let listed = ListedDirectories(sessions: ["/repo": ["s-main"]])
        let marks = makeMarks(load: .stopped, listed: listed)
        marks.setAddedPaths(["/wt/b"])

        let update = await marks.load(addedPath: "/wt/b")

        #expect(update.inventory == .unloaded)
        #expect(update.directories.isEmpty)
        #expect(update.extras.map(\.id) == ["s-main"])
    }

    @Test func v2NeverListsExtras() async {
        let listed = ListedDirectories(sessions: ["/wt/a": ["s-a"]])
        let marks = makeMarks(load: .loaded(directories: ["/repo", "/wt/a"], listsSessions: false), listed: listed)
        marks.setAddedPaths(["/repo"])

        let update = await marks.load(addedPath: "/repo")
        let refreshed = await marks.refreshExtras()

        #expect(update.extras.isEmpty)
        #expect(refreshed.isEmpty)
        #expect(await listed.calls.isEmpty)
    }

    @Test func refreshExtrasRefetchesTheExtraDirectories() async {
        let listed = ListedDirectories(sessions: ["/repo": ["s-main"], "/wt/a": ["s-a"]])
        let marks = makeMarks(load: .loaded(directories: ["/wt/a"], listsSessions: true), listed: listed)
        marks.setAddedPaths(["/wt/b"])
        _ = await marks.load(addedPath: "/wt/b")
        await listed.set(["/repo": ["s-main"], "/wt/a": ["s-a", "s-new"]])

        let updates = await marks.refreshExtras()

        #expect(updates.map(\.addedPath) == ["/wt/b"])
        #expect(Set(updates.first?.extras.map(\.id) ?? []) == ["s-main", "s-a", "s-new"])
        #expect(updates.first?.directories == ["/wt/a"])
        #expect(await listed.calls == ["/repo", "/wt/a", "/repo", "/wt/a"])
    }

    @Test func globalProjectListsNoMainCheckout() async {
        let listed = ListedDirectories(sessions: [:])
        let marks = HomeWorktreeMarks(
            repository: FixedWorktreeRepository(load: .loaded(directories: [], listsSessions: true)),
            resolveProject: { _ in HomeProjectResolution(id: "global", canonical: "/") },
            listSessions: { directory in await sessionItems(listed.list(directory), in: directory) }
        )
        marks.setAddedPaths(["/notes"])

        let update = await marks.load(addedPath: "/notes")

        #expect(update.extras.isEmpty)
        #expect(await listed.calls.isEmpty)
    }

    private func makeMarks(load: WorktreeLoad, listed: ListedDirectories) -> HomeWorktreeMarks {
        let resolution = resolution
        return HomeWorktreeMarks(
            repository: FixedWorktreeRepository(load: load),
            resolveProject: { _ in resolution },
            listSessions: { directory in await sessionItems(listed.list(directory), in: directory) }
        )
    }
}

private struct FixedWorktreeRepository: WorktreeRepository {
    let load: WorktreeLoad

    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { load }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { load }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { load }
    func onDisconnected() {}
    func onConnection(key: String) {}
}

private actor ListedDirectories {
    private var sessions: [String: [String]]
    private(set) var calls: [String] = []

    init(sessions: [String: [String]]) {
        self.sessions = sessions
    }

    func set(_ sessions: [String: [String]]) {
        self.sessions = sessions
    }

    func list(_ directory: String) -> [String] {
        calls.append(directory)
        return sessions[directory] ?? []
    }
}

@MainActor
private func sessionItems(_ ids: [String], in directory: String) -> [SessionListItem] {
    ids.map { id in SessionListItem(id: id, title: id, worktree: directory, directory: directory) }
}
