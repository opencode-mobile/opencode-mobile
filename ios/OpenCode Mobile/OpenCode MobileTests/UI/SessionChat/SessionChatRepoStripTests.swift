import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionChatRepoStripTests {
    private let dirty: [VcsFileStatus] = [
        VcsFileStatus(path: "a/HealthApi.kt", status: .modified, additions: 42, deletions: 7),
        VcsFileStatus(path: "docs/probe.md", status: .deleted, additions: 0, deletions: 19),
    ]

    @Test func newSessionDirtyTreeStartsExpanded() async {
        let region = makeRegion(RepoStripSessionRepository(info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"), status: dirty), startExpanded: true)
        region.start()
        await settle { region.state.isAvailable }

        #expect(region.state.branch == "fix/probe")
        #expect(region.state.defaultBranch == "main")
        #expect(region.state.files == dirty)
        #expect(region.state.isExpanded)
    }

    @Test func activeSessionDirtyTreeStartsCollapsed() async {
        let region = makeRegion(RepoStripSessionRepository(info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"), status: dirty), startExpanded: false)
        region.start()
        await settle { region.state.isAvailable }

        #expect(!region.state.isExpanded)
    }

    @Test func cleanTreeNeverExpands() async {
        let region = makeRegion(RepoStripSessionRepository(info: VcsInfo(currentBranch: "main", defaultBranch: "main"), status: []), startExpanded: true)
        region.start()
        await settle { region.state.isAvailable }

        #expect(!region.state.isExpanded)
        region.toggle()
        #expect(!region.state.isExpanded)
    }

    @Test func noVcsIsUnavailable() async {
        let repository = RepoStripSessionRepository(infoError: SessionError.http(statusCode: 404, message: "no vcs"))
        let region = makeRegion(repository, startExpanded: true)
        region.start()
        await settle { repository.infoCalls >= 1 }
        try? await Task.sleep(for: .milliseconds(20))

        #expect(!region.state.isAvailable)
    }

    @Test func statusRouteMissingShowsBranchRowOnly() async {
        let repository = RepoStripSessionRepository(
            info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"),
            statusError: SessionError.http(statusCode: 404, message: "no status")
        )
        let region = makeRegion(repository, startExpanded: true)
        region.start()
        await settle { region.state.isAvailable }

        #expect(region.state.branch == "fix/probe")
        #expect(region.state.files.isEmpty)
    }

    @Test func toggleFlipsAndSurvivesRefresh() async {
        let region = makeRegion(RepoStripSessionRepository(info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"), status: dirty), startExpanded: false)
        region.start()
        await settle { region.state.isAvailable }

        region.toggle()
        #expect(region.state.isExpanded)

        region.refresh()
        try? await Task.sleep(for: .milliseconds(30))
        #expect(region.state.isExpanded)
    }

    @Test func turnCompletionRefetchesStatus() async {
        let repository = RepoStripSessionRepository(info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"), status: dirty)
        let region = makeRegion(repository, startExpanded: false)
        region.start()
        await settle { region.state.isAvailable }
        #expect(repository.statusCalls == 1)

        region.updateTurnActive(true)
        try? await Task.sleep(for: .milliseconds(20))
        #expect(repository.statusCalls == 1)

        repository.status = Array(dirty.prefix(1))
        region.updateTurnActive(false)
        await settle { repository.statusCalls == 2 }
        await settle { region.state.files.count == 1 }
        #expect(region.state.files == Array(dirty.prefix(1)))
    }

    @Test func branchUpdatedEventRefetchesInfo() async {
        let repository = RepoStripSessionRepository(info: VcsInfo(currentBranch: "fix/probe", defaultBranch: "main"), status: dirty)
        let events = FakeForegroundEventStream()
        let region = makeRegion(repository, startExpanded: false, events: events)
        region.start()
        await settle { region.state.isAvailable }
        #expect(region.state.branch == "fix/probe")

        repository.info = VcsInfo(currentBranch: "main", defaultBranch: "main")
        events.emit(OpencodeEvent(type: SessionChatRepoStrip.branchUpdatedEvent, sessionID: nil))
        await settle { region.state.branch == "main" }
        #expect(region.state.branch == "main")
        region.stop()
    }

    private func makeRegion(
        _ repository: RepoStripSessionRepository,
        startExpanded: Bool,
        events: FakeForegroundEventStream? = nil
    ) -> SessionChatRepoStrip {
        SessionChatRepoStrip(
            worktree: "/repo",
            startExpanded: startExpanded,
            sessionRepository: repository,
            foregroundEventStream: events
        )
    }

    private func settle(timeoutMs: Int = 2000, _ predicate: @escaping @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        while !predicate(), Date() < deadline {
            try? await Task.sleep(for: .milliseconds(5))
        }
    }
}

private final class RepoStripSessionRepository: SessionRepository, @unchecked Sendable {
    var info: VcsInfo?
    var status: [VcsFileStatus]
    private let infoError: Error?
    private let statusError: Error?
    private(set) var infoCalls = 0
    private(set) var statusCalls = 0

    init(info: VcsInfo? = nil, status: [VcsFileStatus] = [], infoError: Error? = nil, statusError: Error? = nil) {
        self.info = info
        self.status = status
        self.infoError = infoError
        self.statusError = statusError
    }

    func getVcsInfo(directory: String?) async throws -> VcsInfo {
        infoCalls += 1
        if let infoError { throw infoError }
        guard let info else { throw SessionError.http(statusCode: 404, message: "no vcs") }
        return info
    }

    func getVcsStatus(directory: String?) async throws -> [VcsFileStatus] {
        statusCalls += 1
        if let statusError { throw statusError }
        return status
    }

    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { [] }
    func getVcsDiff(mode: VcsDiffMode, directory: String?, context: Int) async throws -> [FileDiff] { [] }
    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] { [] }
    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { false }
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
