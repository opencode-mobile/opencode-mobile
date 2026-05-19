import Testing
@testable import OpenCode_Mobile

@MainActor
struct ProjectListHomeViewModelTests {
    @Test func loadSessions_success() async {
        let viewModel = ProjectListHomeViewModel(sessionRepository: FakeSessionRepository())
        await viewModel.loadSessions()
        guard case let .loaded(sessions) = viewModel.state else {
            Issue.record("Expected loaded state")
            return
        }
        #expect(sessions.count == 1)
        #expect(sessions[0].title == "Alpha")
    }

    @Test func loadSessions_error() async {
        let viewModel = ProjectListHomeViewModel(sessionRepository: FakeSessionRepository(shouldFail: true))
        await viewModel.loadSessions()
        guard case .error = viewModel.state else {
            Issue.record("Expected error state")
            return
        }
    }
}

private struct FakeSessionRepository: SessionRepository {
    let shouldFail: Bool

    init(shouldFail: Bool = false) {
        self.shouldFail = shouldFail
    }

    func listSessions() async throws -> [Session] {
        if shouldFail {
            throw SessionError.transport("network")
        }
        return [Session(id: "1", title: "Alpha", slug: nil, projectID: nil, directory: nil, parentID: nil)]
    }

    func createSession(parentID: String?, title: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses() async throws -> [SessionStatus] { fatalError() }
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
