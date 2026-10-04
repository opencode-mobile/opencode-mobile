import Foundation
import Testing
@testable import OpenCode_Mobile

struct T3EngineRepositoryTests {
    // MARK: - Fixtures

    private static func snapshot() -> T3ShellSnapshot {
        T3ShellSnapshot(
            sequence: 1,
            threads: [
                thread(id: "t-alpha-root", projectID: "p-alpha", worktree: nil),
                thread(id: "t-alpha-wt", projectID: "p-alpha", worktree: "/repos/alpha/.worktrees/feature"),
                thread(id: "t-beta", projectID: "p-beta", worktree: nil, activeRunID: "run-1"),
            ],
            projects: [
                T3Project(id: "p-alpha", title: "Alpha", workspaceRoot: "/repos/alpha", defaultModelSelection: nil),
                T3Project(id: "p-beta", title: "", workspaceRoot: "/repos/beta", defaultModelSelection: nil),
            ]
        )
    }

    private static func thread(
        id: String,
        projectID: String,
        worktree: String?,
        activeRunID: String? = nil,
        pendingRequestID: String? = nil
    ) -> T3Thread {
        T3Thread(
            id: id,
            title: "Thread \(id)",
            projectID: projectID,
            status: "idle",
            archivedAt: nil,
            settledAt: nil,
            settledOverride: nil,
            deletedAt: nil,
            activeRunID: activeRunID,
            activityRunStatus: nil,
            providerInstanceID: nil,
            runtimeMode: nil,
            interactionMode: nil,
            modelSelection: nil,
            worktreePath: worktree,
            updatedAtEpochMs: 1,
            createdAtEpochMs: 1,
            pendingRequestID: pendingRequestID,
            pendingRequestKind: pendingRequestID == nil ? nil : "tool-approval"
        )
    }

    // MARK: - Directory filtering

    @Test func listSessions_filtersByWorktreeOrProjectRoot() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let alpha = try await repository.listSessions(directory: "/repos/alpha")
        #expect(alpha.map(\.id) == ["t-alpha-root", "t-alpha-wt"])
        #expect(alpha.first?.directory == "/repos/alpha")
        #expect(alpha.last?.directory == "/repos/alpha/.worktrees/feature")

        let beta = try await repository.listSessions(directory: "/repos/beta")
        #expect(beta.map(\.id) == ["t-beta"])

        let worktreeOnly = try await repository.listSessions(directory: "/repos/alpha/.worktrees/feature")
        #expect(worktreeOnly.map(\.id) == ["t-alpha-wt"])

        let all = try await repository.listSessions(directory: nil)
        #expect(all.count == 3)
    }

    @Test func getAllSessionStatuses_filtersByDirectory() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let beta = try await repository.getAllSessionStatuses(directory: "/repos/beta")
        #expect(beta.map(\.sessionID) == ["t-beta"])
        #expect(beta.first?.raw["type"] == "busy")

        let alpha = try await repository.getAllSessionStatuses(directory: "/repos/alpha")
        #expect(alpha.map(\.sessionID) == ["t-alpha-root", "t-alpha-wt"])
    }

    @Test func rootStrategyThread_usesProjectRootAsDirectory() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let session = try await repository.getSession(id: "t-beta", directory: nil)
        #expect(session.directory == "/repos/beta")
    }

    // MARK: - Project selection on create

    @Test func createSession_launchesInProjectMatchingDirectory() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let session = try await repository.createSession(parentID: nil, title: "Beta work", directory: "/repos/beta")
        #expect(client.launchedProjectIDs == ["p-beta"])
        #expect(session.directory == "/repos/beta")
    }

    @Test func createSession_matchesProjectByThreadWorktree() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        _ = try await repository.createSession(
            parentID: nil,
            title: nil,
            directory: "/repos/alpha/.worktrees/feature"
        )
        #expect(client.launchedProjectIDs == ["p-alpha"])
    }

    @Test func createSession_fallsBackToFirstProject() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let session = try await repository.createSession(parentID: nil, title: nil, directory: "/elsewhere")
        #expect(client.launchedProjectIDs == ["p-alpha"])
        #expect(session.directory == "/elsewhere")
    }

    @Test func createSession_withoutProjects_throws() async {
        let client = FakeT3EngineClient(snapshot: T3ShellSnapshot(sequence: 0, threads: [], projects: []))
        let repository = T3SessionRepository(client: client)
        await #expect(throws: SessionError.http(statusCode: 400, message: "This T3 environment has no projects")) {
            _ = try await repository.createSession(parentID: nil, title: nil, directory: nil)
        }
    }

    // MARK: - Rename and abort

    @Test func updateSession_returnsRequestedTitle() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        let session = try await repository.updateSession(id: "t-beta", title: "  Renamed  ", directory: nil)
        #expect(session.title == "Renamed")
        #expect(client.dispatched.count == 1)
        #expect(client.dispatched.first?["type"] as? String != nil)
        #expect(client.forcedShellFetches >= 1)
    }

    @Test func abortSession_withoutActiveRun_succeedsWithoutDispatch() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        #expect(try await repository.abortSession(id: "t-alpha-root", directory: nil) == true)
        #expect(client.dispatched.isEmpty)

        #expect(try await repository.abortSession(id: "t-beta", directory: nil) == true)
        #expect(client.dispatched.count == 1)
    }

    @Test func respondToPermission_refreshesShellBeforeMatching() async throws {
        var stale = Self.snapshot()
        stale.threads[0] = Self.thread(id: "t-alpha-root", projectID: "p-alpha", worktree: nil, pendingRequestID: "req-old")
        var fresh = Self.snapshot()
        fresh.threads[0] = Self.thread(id: "t-alpha-root", projectID: "p-alpha", worktree: nil, pendingRequestID: "req-new")
        let client = FakeT3EngineClient(snapshot: fresh, cachedSnapshot: stale)
        let repository = T3SessionRepository(client: client)

        let accepted = try await repository.respondToPermission(
            sessionID: "t-alpha-root",
            permissionID: "req-new",
            decision: .allowOnce,
            directory: nil
        )
        #expect(accepted)
        #expect(client.dispatched.count == 1)
    }

    // MARK: - Unsupported operations

    @Test func unsupportedOperations_throwClearErrors() async {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3SessionRepository(client: client)

        await #expect(throws: SessionError.http(statusCode: 400, message: "Share is not available on T3")) {
            _ = try await repository.shareSession(id: "t-beta")
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Share is not available on T3")) {
            _ = try await repository.unshareSession(id: "t-beta")
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Undo is not available on T3")) {
            _ = try await repository.revertSession(id: "t-beta", messageID: "m", partID: nil)
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Undo is not available on T3")) {
            _ = try await repository.unrevertSession(id: "t-beta")
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Compact is not available on T3")) {
            _ = try await repository.summarizeSession(id: "t-beta", providerID: "p", modelID: "m")
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Agents is not available on T3")) {
            _ = try await repository.initializeSessionAgents(id: "t-beta", messageID: "m", providerID: "p", modelID: "m")
        }
        await #expect(throws: SessionError.http(statusCode: 400, message: "Fork is not available on T3")) {
            _ = try await repository.forkSession(id: "t-beta", atMessageID: nil)
        }
    }

    // MARK: - Projects

    @Test func t3ProjectRepository_listsProjectsFromShell() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let repository = T3ProjectRepository(
            client: client,
            activeConnectionManager: ActiveConnectionManager(),
            connectionProfileStore: RecordingProfileStore()
        )

        let projects = try await repository.listProjects()
        #expect(projects.map(\.id) == ["p-alpha", "p-beta"])
        #expect(projects.map(\.worktree) == ["/repos/alpha", "/repos/beta"])
        #expect(projects.map(\.displayName) == ["Alpha", "beta"])

        let current = try await repository.getCurrentProject(directory: "/repos/beta")
        #expect(current.id == "p-beta")
        let unknown = try await repository.getCurrentProject(directory: "/other/dir")
        #expect(unknown.worktree == "/other/dir")
        #expect(unknown.displayName == "dir")
    }

    @Test func t3ProjectRepository_probeSeedsWorktreesWithoutDroppingUserOnes() async throws {
        let client = FakeT3EngineClient(snapshot: Self.snapshot())
        let manager = ActiveConnectionManager()
        manager.apply(
            ConnectionProfile(
                baseURL: "http://127.0.0.1:3000/",
                username: T3Auth.username,
                password: "bearer",
                engine: .t3
            )
        )
        let store = RecordingProfileStore(initial: ["/manual", "/repos/beta"])
        let repository = T3ProjectRepository(
            client: client,
            activeConnectionManager: manager,
            connectionProfileStore: store
        )

        try await repository.probeServer()
        #expect(client.forcedShellFetches == 1)
        #expect(store.worktrees == ["/manual", "/repos/beta", "/repos/alpha"])
    }

    @Test func engineProjectRepository_delegatesByProfileEngine() async throws {
        let opencode = TaggedProjectRepository(tag: "opencode")
        let t3 = TaggedProjectRepository(tag: "t3")
        let manager = ActiveConnectionManager()
        manager.apply(
            ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "", engine: .opencode)
        )
        let repository = EngineProjectRepository(opencode: opencode, t3: t3, activeConnectionManager: manager)

        try await repository.probeServer()
        #expect(try await repository.listProjects().map(\.id) == ["opencode"])
        #expect(try await repository.getCurrentProject(directory: "/x").id == "opencode")
        #expect(opencode.probeCalls == 1)
        #expect(t3.probeCalls == 0)

        manager.apply(
            ConnectionProfile(baseURL: "http://127.0.0.1:3000/", username: T3Auth.username, password: "b", engine: .t3)
        )
        try await repository.probeServer()
        #expect(try await repository.listProjects().map(\.id) == ["t3"])
        #expect(try await repository.getCurrentProject(directory: "/x").id == "t3")
        #expect(opencode.probeCalls == 1)
        #expect(t3.probeCalls == 1)
    }
}

// MARK: - Fakes

private final class FakeT3EngineClient: T3EngineClient, @unchecked Sendable {
    private let lock = NSLock()
    private var snapshot: T3ShellSnapshot
    private var cachedSnapshot: T3ShellSnapshot
    private(set) var forcedShellFetches = 0
    private(set) var dispatched: [[String: Any]] = []
    private(set) var launchedProjectIDs: [String?] = []

    init(snapshot: T3ShellSnapshot, cachedSnapshot: T3ShellSnapshot? = nil) {
        self.snapshot = snapshot
        self.cachedSnapshot = cachedSnapshot ?? snapshot
    }

    func fetchShell(force: Bool) async throws -> T3ShellSnapshot {
        lock.lock(); defer { lock.unlock() }
        if force {
            forcedShellFetches += 1
            cachedSnapshot = snapshot
            return snapshot
        }
        return cachedSnapshot
    }

    func cachedThread(id: String) -> T3Thread? {
        lock.lock(); defer { lock.unlock() }
        return cachedSnapshot.thread(id: id)
    }

    func fetchThread(id: String) async throws -> T3ThreadDetail {
        lock.lock(); defer { lock.unlock() }
        return T3ThreadDetail(sequence: 0, thread: snapshot.thread(id: id), messages: [], turnItems: [])
    }

    func dispatchCommand(_ command: [String: Any]) async throws {
        lock.lock(); defer { lock.unlock() }
        dispatched.append(command)
    }

    func launchThread(title: String?, projectID: String?) async throws -> Session {
        lock.lock(); defer { lock.unlock() }
        launchedProjectIDs.append(projectID)
        return Session(
            id: "t-new",
            title: title ?? "New thread",
            slug: nil,
            projectID: projectID,
            directory: nil,
            parentID: nil,
            updatedAtEpochMs: 1
        )
    }
}

private final class RecordingProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private(set) var worktrees: [String]

    init(initial: [String] = []) {
        self.worktrees = initial
    }

    func activeProfile() async -> ConnectionProfile? { nil }
    func saveProfile(_: ConnectionProfile) async throws {}
    func applyProfile(id _: String) async {}
    func removeProfile(id _: String) async {}
    func addedWorktrees(serverBaseURL _: String) async -> [String] { worktrees }
    func addWorktree(serverBaseURL _: String, worktree: String) async {
        if !worktrees.contains(worktree) { worktrees.append(worktree) }
    }
    func removeWorktree(serverBaseURL _: String, worktree: String) async {
        worktrees.removeAll { $0 == worktree }
    }
    func setAddedWorktrees(serverBaseURL _: String, worktrees: [String]) async {
        self.worktrees = worktrees
    }
    func clearAll() async { worktrees.removeAll() }
}

private final class TaggedProjectRepository: ProjectRepository, @unchecked Sendable {
    let tag: String
    private(set) var probeCalls = 0

    init(tag: String) {
        self.tag = tag
    }

    func probeServer() async throws { probeCalls += 1 }

    func listProjects() async throws -> [Project] {
        [Project(id: tag, worktree: "/\(tag)", displayName: tag)]
    }

    func getCurrentProject(directory _: String?) async throws -> Project {
        Project(id: tag, worktree: "/\(tag)", displayName: tag)
    }
}
