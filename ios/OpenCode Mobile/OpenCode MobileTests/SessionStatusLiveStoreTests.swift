import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionStatusLiveStoreTests {
    @Test func observeStatuses_appliesStatusEventLocallyWithoutWaitingForGet() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = StatusCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission(matching: { _ in true })
        let fetchesAfterBootstrap = repository.statusFetchCount

        eventStream.emit(
            OpencodeEvent(
                type: "session.status",
                sessionID: "ses-1",
                rawProperties: [
                    "sessionID": .string("ses-1"),
                    "status": .dictionary(["type": .string("busy")]),
                ]
            )
        )
        let statuses = try await collector.awaitEmission { $0["ses-1"]?.raw["type"] == "busy" }
        collector.cancel()

        #expect(statuses["ses-1"]?.raw["type"] == "busy")
        #expect(repository.statusFetchCount == fetchesAfterBootstrap)
    }

    @Test func observeStatuses_removesSessionOnIdleEvent() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = StatusCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0["ses-1"]?.raw["type"] == "busy" }

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let statuses = try await collector.awaitEmission { $0.isEmpty }
        collector.cancel()

        #expect(statuses.isEmpty)
    }

    @Test func observeStatuses_keepsPriorWhenStatusLoadFails() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = StatusCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0["ses-1"]?.raw["type"] == "busy" }
        let emissionsBeforeReconnect = collector.emissions.count

        repository.shouldFailStatuses = true
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        try await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs + 80))
        collector.cancel()

        #expect(collector.emissions.count == emissionsBeforeReconnect)
        #expect(collector.emissions.last?["ses-1"]?.raw["type"] == "busy")
    }
}

@MainActor
private final class StatusCollector {
    private(set) var emissions: [[String: SessionStatus]] = []
    private var task: Task<Void, Never>?

    init(store: DefaultSessionStatusLiveStore, directory: String) {
        task = Task {
            for await statuses in store.observeStatuses(directory: directory) {
                emissions.append(statuses)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping ([String: SessionStatus]) -> Bool
    ) async throws -> [String: SessionStatus] {
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

private final class StatusLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    var statuses: [SessionStatus]
    var statusFetchCount = 0
    var shouldFailStatuses = false

    init(statuses: [SessionStatus]) {
        self.statuses = statuses
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        statusFetchCount += 1
        if shouldFailStatuses { throw SessionError.http(statusCode: 500, message: "status failed") }
        return statuses
    }
    func getSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func updateSession(id: String, title: String?, directory: String?) async throws -> Session { fatalError() }
    func archiveSession(id: String, directory: String?) async throws -> Session { fatalError() }
    func deleteSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id: String) async throws -> [Session] { fatalError() }
    func listSessionTodos(id: String, directory: String?) async throws -> [SessionTodo] { fatalError() }
    func initializeSessionAgents(id: String, messageID: String, providerID: String, modelID: String) async throws -> Bool { fatalError() }
    func forkSession(id: String, atMessageID: String?) async throws -> Session { fatalError() }
    func abortSession(id: String, directory: String?) async throws -> Bool { fatalError() }
    func shareSession(id: String) async throws -> Session { fatalError() }
    func unshareSession(id: String) async throws -> Session { fatalError() }
    func getSessionDiff(id: String, messageID: String?, directory: String?) async throws -> [FileDiff] { fatalError() }
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
