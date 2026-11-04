import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct ArchivedSessionsViewModelTests {
    private let now: Int64 = 100 * day
    private let alpha = project("p_alpha", "alpha", "/work/alpha")
    private let beta = project("p_beta", "beta", "/work/beta")

    @Test func groupsByArchiveTimeNewestFirst() async throws {
        let repository = ArchivedSessionRepository([
            "/work/alpha": [
                archived("old", "/work/alpha", now - 20 * day),
                archived("recent", "/work/alpha", now - 2 * day),
                archived("newest", "/work/alpha", now - day),
                archived("child", "/work/alpha", now - day, parentID: "newest"),
            ],
        ])
        let viewModel = makeViewModel(repository)

        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)

        let sections = try #require(loadedSections(viewModel))
        #expect(sections.map(\.period) == [.thisWeek, .earlier])
        #expect(sections[0].rows.map(\.id) == ["newest", "recent"])
        #expect(sections[1].rows.map(\.id) == ["old"])
        #expect(viewModel.scopeTitle == "All projects")
    }

    @Test func projectScopeShowsItsSessionsAndOffersAllProjectsWhenEmpty() async throws {
        let repository = ArchivedSessionRepository([
            "/work/alpha": [archived("a1", "/work/alpha", now - day)],
            "/work/beta": [],
        ])
        let viewModel = makeViewModel(repository)

        await viewModel.appear(scopeID: beta.key, projects: [alpha, beta], worktreesEnabled: true)

        #expect(viewModel.content == .loaded(sections: [], canShowAllProjects: true))
        #expect(viewModel.scopeTitle == "beta")

        viewModel.showAllProjects()

        let sections = try #require(loadedSections(viewModel))
        #expect(sections.flatMap { $0.rows.map(\.id) } == ["a1"])
        #expect(viewModel.scopeTitle == "All projects")
    }

    @Test func emptyProjectWithNothingElseArchivedOffersNoWayOut() async {
        let viewModel = makeViewModel(ArchivedSessionRepository(["/work/alpha": [], "/work/beta": []]))

        await viewModel.appear(scopeID: alpha.key, projects: [alpha, beta], worktreesEnabled: true)

        #expect(viewModel.content == .loaded(sections: [], canShowAllProjects: false))
    }

    @Test func unarchiveRemovesTheRowAndUndoArchivesItAgain() async throws {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now - day, title: "Fix login")]])
        let viewModel = makeViewModel(repository)
        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)

        await viewModel.unarchive(sessionID: "a1")

        #expect(rowIDs(viewModel).isEmpty)
        #expect(repository.unarchivedIDs == ["a1"])
        let snackbar = try #require(viewModel.snackbar)
        #expect(snackbar.message.text == "Unarchived \u{201C}Fix login\u{201D}")
        #expect(snackbar.undoSessionID == "a1")

        await viewModel.undoUnarchive(sessionID: "a1")

        #expect(repository.archivedIDs == ["a1"])
        #expect(rowIDs(viewModel) == ["a1"])
    }

    @Test func failedUnarchiveBringsTheRowBackWithAnError() async throws {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now - day)]])
        repository.unarchiveError = SessionError.transport("offline")
        let viewModel = makeViewModel(repository)
        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)

        await viewModel.unarchive(sessionID: "a1")

        #expect(rowIDs(viewModel) == ["a1"])
        let snackbar = try #require(viewModel.snackbar)
        #expect(snackbar.message.variant == .error)
        #expect(snackbar.undoSessionID == nil)
    }

    @Test func sheetOpenReturnsTheRowAndDeleteConfirmsFirst() async throws {
        let repository = ArchivedSessionRepository([
            "/work/alpha": [archived("a1", "/work/alpha", now - day), archived("a2", "/work/alpha", now - 2 * day)],
        ])
        let viewModel = makeViewModel(repository)
        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)
        let first = try #require(loadedSections(viewModel)?.first?.rows.first)

        viewModel.openActions(first)
        #expect(viewModel.sheetVisible)
        #expect(viewModel.chooseOpen()?.id == "a1")
        #expect(!viewModel.sheetVisible)

        viewModel.openActions(first)
        viewModel.chooseDelete()
        viewModel.dismissActions()
        #expect(viewModel.deleteConfirmVisible)
        #expect(viewModel.deleteTarget?.id == "a1")
        #expect(repository.deletedIDs.isEmpty)
        await viewModel.confirmDelete()

        #expect(repository.deletedIDs == ["a1"])
        #expect(rowIDs(viewModel) == ["a2"])
    }

    @Test func liveArchiveEventRefreshesTheList() async throws {
        let repository = ArchivedSessionRepository(["/work/alpha": []])
        let stream = FakeForegroundEventStream()
        let viewModel = makeViewModel(repository, eventStream: stream)
        let appear = Task { await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true) }
        defer { appear.cancel() }
        #expect(await waitUntil { viewModel.content == .loaded(sections: [], canShowAllProjects: false) })

        repository.lists["/work/alpha"] = [archived("a1", "/work/alpha", now - day)]
        stream.emit(OpencodeEvent(type: "session.archived", sessionID: "a1"))

        #expect(await waitUntil { rowIDs(viewModel) == ["a1"] })
    }

    @Test func failedFirstLoadShowsRetry() async {
        let repository = ArchivedSessionRepository(["/work/alpha": []])
        repository.listError = SessionError.transport("offline")
        let viewModel = makeViewModel(repository)

        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)
        guard case .failed = viewModel.content else {
            Issue.record("expected the failed state")
            return
        }

        repository.listError = nil
        await viewModel.retry()
        #expect(viewModel.content == .loaded(sections: [], canShowAllProjects: false))
    }

    @Test func archivedSessionListSkipsAFailingDirectory() async throws {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now)], "/work/beta": []])
        repository.failingDirectories = ["/work/beta"]

        let sessions = try await ArchivedSessionList.load(repository: repository, directories: ["/work/alpha", "/work/beta"])

        #expect(sessions.map(\.id) == ["a1"])
    }

    @Test func unarchiveLeavesTheServerReadStateAlone() async {
        // Home's Done pill is server unread state; only opening the session in Session Chat acknowledges it.
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now - day)]])
        let viewModel = makeViewModel(repository)
        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)

        await viewModel.unarchive(sessionID: "a1")

        #expect(repository.unarchivedIDs == ["a1"])
        #expect(repository.viewedIDs.isEmpty)
    }

    @Test func aLaggingListDoesNotBringAnUnarchivedRowBack() async {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now - day)]])
        repository.unarchiveLags = true
        let stream = FakeForegroundEventStream()
        let viewModel = makeViewModel(repository, eventStream: stream)
        let appear = Task { await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true) }
        defer { appear.cancel() }
        #expect(await waitUntil { rowIDs(viewModel) == ["a1"] })

        await viewModel.unarchive(sessionID: "a1")
        stream.emit(OpencodeEvent(type: "session.unarchived", sessionID: "a1"))
        try? await Task.sleep(for: .milliseconds(Int(ArchivedSessionsViewModel.refreshDebounceMs) + 200))

        #expect(rowIDs(viewModel).isEmpty)
    }

    @Test func aFailingProjectKeepsTheOthersListed() async {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/work/alpha", now - day)], "/work/beta": []])
        repository.failingDirectories = ["/work/beta"]
        let viewModel = makeViewModel(repository)

        await viewModel.appear(scopeID: "all", projects: [alpha, beta], worktreesEnabled: true)

        #expect(rowIDs(viewModel) == ["a1"])
    }

    @Test func undoKeepsARowWhoseDirectoryIsUnknown() async {
        let repository = ArchivedSessionRepository(["/work/alpha": [archived("a1", "/elsewhere", now - day)]])
        let viewModel = makeViewModel(repository)
        await viewModel.appear(scopeID: "all", projects: [alpha], worktreesEnabled: true)
        await viewModel.unarchive(sessionID: "a1")

        let undo = Task { await viewModel.undoUnarchive(sessionID: "a1") }
        #expect(await waitUntil { rowIDs(viewModel) == ["a1"] })
        await undo.value
    }

    @Test func homeMenuEntryCountsTheScopeAndHidesWithoutSupport() async {
        let repository = ArchivedSessionRepository([
            "/work/alpha": [archived("a1", "/work/alpha", now - day), archived("a2", "/work/alpha", now - day)],
            "/work/beta": [archived("b1", "/work/beta", now - day)],
        ])
        var supported = true
        let menu = HomeArchivedMenu(sessionRepository: repository, supported: { supported })

        menu.refresh(scopeID: "all", projects: [alpha, beta], worktreesEnabled: true)
        #expect(menu.entry?.count == nil)
        await menu.settle()
        #expect(menu.entry == HomeArchivedMenuEntry(scopeID: "all", scopeTitle: "All projects", count: 3))

        menu.refresh(scopeID: beta.key, projects: [alpha, beta], worktreesEnabled: true)
        await menu.settle()
        #expect(menu.entry == HomeArchivedMenuEntry(scopeID: beta.key, scopeTitle: "beta", count: 1))

        supported = false
        menu.refresh(scopeID: "all", projects: [alpha, beta], worktreesEnabled: true)
        #expect(menu.entry == nil)

        // Capabilities that arrive after the menu opened bring the entry back without reopening it.
        supported = true
        menu.recheck(projects: [alpha, beta])
        await menu.settle()
        #expect(menu.entry?.count == 3)
    }

    private func makeViewModel(
        _ repository: ArchivedSessionRepository,
        eventStream: (any ForegroundEventStream)? = nil
    ) -> ArchivedSessionsViewModel {
        let now = now
        return ArchivedSessionsViewModel(sessionRepository: repository, eventStream: eventStream, now: { now })
    }

    private func loadedSections(_ viewModel: ArchivedSessionsViewModel) -> [ArchivedSessionSection]? {
        if case let .loaded(sections, _) = viewModel.content { return sections }
        return nil
    }

    private func rowIDs(_ viewModel: ArchivedSessionsViewModel) -> [String] {
        loadedSections(viewModel)?.flatMap { $0.rows.map(\.id) } ?? []
    }

    private func waitUntil(timeoutMs: Int = 3_000, _ predicate: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
        while Date() < deadline {
            if predicate() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return predicate()
    }

    private func archived(
        _ id: String,
        _ directory: String,
        _ archivedAt: Int64,
        title: String? = nil,
        parentID: String? = nil
    ) -> Session {
        Session(
            id: id,
            title: title ?? id,
            slug: nil,
            projectID: nil,
            directory: directory,
            parentID: parentID,
            updatedAtEpochMs: archivedAt,
            archivedAtEpochMs: archivedAt
        )
    }
}

private let day: Int64 = 24 * 60 * 60 * 1000

private func project(_ key: String, _ name: String, _ path: String) -> HomeProject {
    HomeProject(key: key, projectID: key, displayName: name, memberPaths: [path], isRepository: false, worktrees: [])
}

private final class ArchivedSessionRepository: SessionRepository, @unchecked Sendable {
    var lists: [String: [Session]]
    var failingDirectories: Set<String> = []
    var listError: Error?
    var unarchiveError: Error?
    /// The archived list keeps an unarchived session for a while, like a lagging server read.
    var unarchiveLags = false
    private(set) var unarchivedIDs: [String] = []
    private(set) var archivedIDs: [String] = []
    private(set) var deletedIDs: [String] = []

    init(_ lists: [String: [Session]]) {
        self.lists = lists
    }

    func listArchivedSessions(directory: String?) async throws -> [Session] {
        if let listError { throw listError }
        if let directory, failingDirectories.contains(directory) { throw SessionError.transport("unreachable") }
        return lists[directory ?? ""] ?? []
    }

    func unarchiveSession(id: String, directory: String?) async throws -> Session {
        if let unarchiveError { throw unarchiveError }
        unarchivedIDs.append(id)
        guard var session = lists.values.flatMap({ $0 }).first(where: { $0.id == id }) else {
            throw SessionError.http(statusCode: 404, message: "not archived")
        }
        if !unarchiveLags { lists = lists.mapValues { $0.filter { $0.id != id } } }
        session.archivedAtEpochMs = nil
        return session
    }

    func archiveSession(id: String, directory: String?) async throws -> Session {
        archivedIDs.append(id)
        let session = Session(id: id, title: id, slug: nil, projectID: nil, directory: directory, parentID: nil, updatedAtEpochMs: 1, archivedAtEpochMs: 1)
        lists[directory ?? "", default: []].append(session)
        return session
    }

    func deleteSession(id: String, directory: String?) async throws -> Bool {
        deletedIDs.append(id)
        lists = lists.mapValues { $0.filter { $0.id != id } }
        return true
    }

    private(set) var viewedIDs: [String] = []

    func markSessionViewed(sessionId: String, idleAtEpochMs _: Int64) async throws {
        viewedIDs.append(sessionId)
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { [] }
    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { false }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { false }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { false }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { false }
    func unrevertSession(id: String) async throws -> Bool { false }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision, directory: String?) async throws -> Bool { false }
}
