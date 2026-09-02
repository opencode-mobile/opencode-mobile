import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite(.serialized)
@MainActor
struct PermissionLiveStoreTests {
    @Test func permissionAskedBurst_isNeitherDelayedNorAmplified() async throws {
        let first = testPermission("perm-1")
        let second = testPermission("perm-2")
        let third = testPermission("perm-3")
        let repository = PermissionLiveStoreFakeSessionRepository(
            script: [
                [],
                [first],
                [first, second],
                [first, second, third],
            ]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }

        let started = ContinuousClock.now
        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1"] }
        #expect(started.duration(to: ContinuousClock.now) < .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))

        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "permission.asked", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1", "perm-2", "perm-3"] }

        #expect(repository.permissionFetchCount == 4)
    }

    @Test func reconnectPair_coalescesIntoOneFetch() async throws {
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }
        #expect(repository.permissionFetchCount == 1)

        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        await settleBeyondDebounce()

        #expect(repository.permissionFetchCount == 2)
    }

    @Test func unchangedList_doesNotReEmit() async throws {
        let request = testPermission("perm-1")
        let repository = PermissionLiveStoreFakeSessionRepository(script: [[request]])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-1"] }
        #expect(collector.emissions.count == 1)

        eventStream.emit(OpencodeEvent(type: "permission.replied", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "permission.replied", sessionID: "ses-1"))
        try await pollUntil { repository.permissionFetchCount == 3 }

        #expect(collector.emissions.count == 1)
        #expect(repository.permissionFetchCount == 3)
    }
}

@MainActor
private final class PermissionCollector {
    private(set) var emissions: [[PermissionRequest]] = []
    private var task: Task<Void, Never>?

    init(store: DefaultPermissionLiveStore, directory: String) {
        task = Task {
            for await requests in store.observePermissions(directory: directory) {
                emissions.append(requests)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping ([PermissionRequest]) -> Bool
    ) async throws -> [PermissionRequest] {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if let match = emissions.last(where: predicate) {
                return match
            }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw PermissionLiveStoreTimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

private struct PermissionLiveStoreTimeoutError: Error {}

@MainActor
private func pollUntil(
    timeout: Duration = .seconds(3),
    predicate: () -> Bool
) async throws {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if predicate() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    throw PermissionLiveStoreTimeoutError()
}

@MainActor
private func settleBeyondDebounce() async {
    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs + 400))
}

private func testPermission(_ id: String) -> PermissionRequest {
    PermissionRequest(
        id: id,
        sessionID: "ses-1",
        permission: "bash",
        patterns: [],
        metadata: [:],
        always: [],
        toolMessageID: nil,
        toolCallID: nil
    )
}

private final class PermissionLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    private let script: [[PermissionRequest]]
    private let lock = NSLock()
    private var index = 0
    private var _permissionFetchCount = 0

    var permissionFetchCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _permissionFetchCount
    }

    init(script: [[PermissionRequest]]) {
        self.script = script
    }

    func listPendingPermissions(directory: String?) async throws -> [PermissionRequest] {
        lock.lock()
        _permissionFetchCount += 1
        let result: [PermissionRequest]
        if script.isEmpty {
            result = []
        } else if index < script.count {
            result = script[index]
            index += 1
        } else {
            result = script[script.count - 1]
        }
        lock.unlock()
        return result
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] { fatalError() }
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
