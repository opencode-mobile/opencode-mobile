import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionTodoLiveStoreTests {
    @Test func observeTodos_fetchesWithoutLive() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: FakeForegroundEventStream()
        )
        let collector = TodoCollector(store: store)
        let todos = try await collector.awaitEmission { !$0.isEmpty }
        collector.cancel()

        #expect(repository.listTodosCallCount == 1)
        #expect(todos.first?.content == "A")
    }

    @Test func setLive_forceFetchesTodos() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: FakeForegroundEventStream()
        )
        let collector = TodoCollector(store: store)
        _ = try await collector.awaitEmission { !$0.isEmpty }

        repository.todos = [SessionTodo(position: 0, content: "B", status: .pending, priority: "low")]
        store.setLive(true, sessionID: "ses-1", directory: "/repo")
        let todos = try await collector.awaitEmission { $0.first?.content == "B" }
        collector.cancel()

        #expect(repository.listTodosCallCount == 2)
        #expect(todos.first?.content == "B")
    }

    @Test func setLiveFalse_keepsCache() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: FakeForegroundEventStream()
        )
        let collector = TodoCollector(store: store)
        _ = try await collector.awaitEmission { !$0.isEmpty }

        store.setLive(false, sessionID: "ses-1", directory: "/repo")
        try await Task.sleep(for: .milliseconds(40))
        collector.cancel()

        #expect(collector.emissions.last?.first?.content == "A")
    }

    @Test func todoUpdated_appliesWhileIdle() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = TodoCollector(store: store)
        _ = try await collector.awaitEmission { !$0.isEmpty }

        eventStream.emit(
            OpencodeEvent(
                type: "todo.updated",
                sessionID: "ses-1",
                rawProperties: [
                    "todos": .array([
                        .dictionary([
                            "content": .string("From SSE"),
                            "status": .string("pending"),
                            "priority": .string("low"),
                        ]),
                    ]),
                ]
            )
        )
        let todos = try await collector.awaitEmission { $0.first?.content == "From SSE" }
        collector.cancel()

        #expect(todos.first?.content == "From SSE")
    }

    @Test func worldForeground_failedCatchUp_keepsStaleForNextForeground() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let world = FakeWorldSignals()
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            worldSignals: world
        )
        let collector = TodoCollector(store: store)
        _ = try await collector.awaitEmission { !$0.isEmpty }

        world.emit(.backgroundStop)
        try await Task.sleep(for: .milliseconds(40))
        repository.listTodosError = URLError(.cannotConnectToHost)
        world.emit(.foreground)
        let failed = await waitUntil { repository.listTodosCallCount >= 2 }
        #expect(failed)
        let afterFail = repository.listTodosCallCount

        repository.listTodosError = nil
        repository.todos = [SessionTodo(position: 0, content: "B", status: .pending, priority: "low")]
        world.emit(.foreground)
        let todos = try await collector.awaitEmission { $0.first?.content == "B" }
        collector.cancel()

        #expect(repository.listTodosCallCount > afterFail)
        #expect(todos.first?.content == "B")
    }

    @Test func reconnect_doesNotFetchAfterObservationEnds() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = TodoCollector(store: store)
        _ = try await collector.awaitEmission { !$0.isEmpty }
        let afterObserve = repository.listTodosCallCount
        collector.cancel()
        try await Task.sleep(for: .milliseconds(40))

        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil, rawProperties: nil))
        try await Task.sleep(for: .milliseconds(40))

        #expect(repository.listTodosCallCount == afterObserve)
    }

    @Test func clearCache_isolatesSameDirectoryAfterProfileSwitch() async throws {
        let repository = TodoLiveStoreFakeSessionRepository(
            todos: [SessionTodo(position: 0, content: "A", status: .pending, priority: "low")]
        )
        let store = DefaultSessionTodoLiveStore(
            sessionRepository: repository,
            eventStream: FakeForegroundEventStream()
        )
        let first = TodoCollector(store: store)
        _ = try await first.awaitEmission { $0.first?.content == "A" }
        first.cancel()
        store.clearCache()
        repository.todos = [SessionTodo(position: 0, content: "B", status: .pending, priority: "low")]
        let second = TodoCollector(store: store)
        defer { second.cancel() }
        _ = try await second.awaitEmission { $0.first?.content == "B" }
        #expect(second.emissions.allSatisfy { $0.allSatisfy { $0.content != "A" } })
    }
}

@MainActor
private final class TodoCollector {
    private(set) var emissions: [[SessionTodo]] = []
    private var task: Task<Void, Never>?

    init(store: DefaultSessionTodoLiveStore) {
        task = Task {
            for await todos in store.observeTodos(sessionID: "ses-1", directory: "/repo") {
                emissions.append(todos)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping ([SessionTodo]) -> Bool
    ) async throws -> [SessionTodo] {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if let match = emissions.last(where: predicate) {
                return match
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw TimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

private struct TimeoutError: Error {}

@MainActor
private func waitUntil(
    timeoutMs: Int = 2_000,
    predicate: @MainActor () -> Bool
) async -> Bool {
    let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1_000)
    while Date() < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return predicate()
}

private final class TodoLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    var todos: [SessionTodo]
    var listTodosCallCount = 0
    var listTodosError: Error?

    init(todos: [SessionTodo]) {
        self.todos = todos
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { [] }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { [] }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] {
        listTodosCallCount += 1
        if let listTodosError { throw listTodosError }
        return todos
    }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func revertSession(id: String, messageID: String, partID: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID: String,
        permissionID: String,
        decision: PermissionDecision,
        directory: String?
    ) async throws -> Bool { fatalError() }
}
