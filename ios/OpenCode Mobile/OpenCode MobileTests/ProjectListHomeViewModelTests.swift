import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectListHomeViewModelTests {
    @Test func refresh_probeFailed() async {
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(shouldFailProbe: true)
        )
        await viewModel.refresh()
        guard case .probeFailed = viewModel.state else {
            Issue.record("Expected probeFailed")
            return
        }
    }

    @Test func refresh_seedsBootWorktree() async {
        let store = FakeConnectionProfileStore()
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(bootWorktree: "/boot"),
            connectionProfileStore: store
        )
        await viewModel.refresh()
        #expect(await store.addedWorktrees(serverBaseURL: "http://127.0.0.1:4096/").contains("/boot"))
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(!sections.isEmpty)
    }

    @Test func refresh_limitsPreviewSessionsToThree() async {
        let sessions = (1 ... 7).map {
            Session(id: "\($0)", title: "S\($0)", slug: nil, projectID: nil, directory: "/repo", parentID: nil, updatedAtEpochMs: Int64($0))
        }
        let viewModel = makeViewModel(
            projectRepository: FakeProjectRepository(),
            sessionRepository: FakeSessionRepository(sessions: sessions),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/repo"])
        )
        await viewModel.refresh()
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.first?.previewSessions.count == 3)
        #expect(sections.first?.totalSessionCount == 7)
    }

    @Test func refresh_preservesAddedWorktreeOrder() async {
        let viewModel = makeViewModel(
            projectRepository: SlowFirstProjectRepository(),
            sessionRepository: SlowFirstSessionRepository(),
            connectionProfileStore: FakeConnectionProfileStore(initial: ["/slow", "/fast"])
        )
        await viewModel.refresh()
        guard case let .loaded(sections, _, _, _) = viewModel.state else {
            Issue.record("Expected loaded")
            return
        }
        #expect(sections.map(\.worktree) == ["/slow", "/fast"])
    }
}

@MainActor
private func makeViewModel(
    projectRepository: any ProjectRepository,
    sessionRepository: any SessionRepository = FakeSessionRepository(),
    connectionProfileStore: FakeConnectionProfileStore = FakeConnectionProfileStore(),
    eventStream: FakeForegroundEventStream = FakeForegroundEventStream()
) -> ProjectListHomeViewModel {
    let manager = ActiveConnectionManager()
    manager.apply(ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: ""))
    let coordinator = ConnectionSessionCoordinator(
        activeConnectionManager: manager,
        connectionProfileStore: connectionProfileStore
    )
    let sessionStatusLiveStore = DefaultSessionStatusLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream
    )
    let sessionLiveStore = DefaultSessionLiveStore(
        sessionRepository: sessionRepository,
        eventStream: eventStream
    )
    return ProjectListHomeViewModel(
        projectRepository: projectRepository,
        sessionRepository: sessionRepository,
        connectionProfileStore: connectionProfileStore,
        activeConnectionManager: manager,
        sessionLiveStore: sessionLiveStore,
        sessionStatusLiveStore: sessionStatusLiveStore,
        foregroundEventStream: eventStream,
        connectionSessionCoordinator: coordinator
    )
}

private struct FakeProjectRepository: ProjectRepository {
    let shouldFailProbe: Bool
    let bootWorktree: String

    init(shouldFailProbe: Bool = false, bootWorktree: String = "/boot") {
        self.shouldFailProbe = shouldFailProbe
        self.bootWorktree = bootWorktree
    }

    func probeServer() async throws {
        if shouldFailProbe { throw ServerProbeError.transport("down") }
    }

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        Project(id: "p1", worktree: directory ?? bootWorktree, displayName: "demo")
    }
}

private final class FakeConnectionProfileStore: ConnectionProfileStore, @unchecked Sendable {
    private var added: [String]

    init(initial: [String] = []) {
        self.added = initial
    }

    func activeProfile() async -> ConnectionProfile? {
        ConnectionProfile(baseURL: "http://127.0.0.1:4096/", username: "opencode", password: "")
    }

    func saveProfile(_ profile: ConnectionProfile) async {}

    func addedWorktrees(serverBaseURL: String) async -> [String] { added }

    func addWorktree(serverBaseURL: String, worktree: String) async {
        if !added.contains(worktree) { added.append(worktree) }
    }

    func setAddedWorktrees(serverBaseURL: String, worktrees: [String]) async {
        var seen = Set<String>()
        added = worktrees.filter { seen.insert($0).inserted }
    }
}

private struct SlowFirstProjectRepository: ProjectRepository {
    func probeServer() async throws {}

    func listProjects() async throws -> [Project] { [] }

    func getCurrentProject(directory: String?) async throws -> Project {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return Project(
            id: directory ?? "p",
            worktree: directory ?? "/unknown",
            displayName: ProjectDomainAdapter.displayName(from: directory ?? "/unknown")
        )
    }
}

private struct SlowFirstSessionRepository: SessionRepository {
    func listSessions(directory: String?) async throws -> [Session] {
        if directory == "/slow" {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return [
            Session(
                id: "1",
                title: "Session",
                slug: nil,
                projectID: nil,
                directory: directory,
                parentID: nil,
                updatedAtEpochMs: 1
            ),
        ]
    }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool { fatalError() }
}

private struct FakeSessionRepository: SessionRepository {
    let sessions: [Session]

    init(sessions: [Session] = [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: nil, parentID: nil, updatedAtEpochMs: 1)]) {
        self.sessions = sessions
    }

    func listSessions(directory: String?) async throws -> [Session] { sessions }

    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?) async throws -> [FileDiff] { fatalError() }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(sessionID: String, permissionID: String, decision: PermissionDecision) async throws -> Bool { fatalError() }
}
