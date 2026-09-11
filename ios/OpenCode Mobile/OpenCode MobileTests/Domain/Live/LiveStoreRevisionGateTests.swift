import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct LiveStoreRevisionGateTests {
    @Test func statusReload_isDiscarded_whenSessionIdleLandsWhileTheGetIsInFlight() async throws {
        let repository = GatedStatusRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = StatusEmissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0["ses-1"]?.raw["type"] == "busy" }
        try await poll { eventStream.hasEventSubscriber }
        #expect(canCatchUp(occupancy: .active, snapshot: eventStream.snapshot))

        let gate = RestGate()
        repository.nextGetGate = gate
        eventStream.openGap()
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        try await poll { repository.statusFetchCount == 2 }

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        _ = try await collector.awaitEmission { $0["ses-1"] == nil }

        gate.open()
        try await poll { repository.completedFetchCount >= 2 }
        for _ in 0..<32 {
            await Task.yield()
        }

        #expect(collector.emissions.last?["ses-1"] == nil)
    }

    @Test func permissionRefreshes_completingOutOfOrder_endWithTheNewestList() async throws {
        let repository = GatedPermissionRepository(
            script: [
                [],
                [],
                [testPermissionRequest("perm-2")],
            ]
        )
        let eventStream = FakeForegroundEventStream()
        eventStream.setConnectionState(.connected)
        let store = DefaultPermissionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = PermissionEmissionCollector(store: store, directory: "/repo")
        defer { collector.cancel() }

        _ = try await collector.awaitEmission { $0.isEmpty }
        try await poll { eventStream.hasEventSubscriber }
        #expect(canCatchUp(occupancy: .active, snapshot: eventStream.snapshot))

        let gate = RestGate()
        repository.nextGetGate = gate
        eventStream.emit(OpencodeEvent(type: "permission.replied", sessionID: "ses-1"))
        try await poll { repository.permissionFetchCount == 2 }

        store.invalidate(directory: "/repo")
        #expect(repository.permissionFetchCount == 2)

        gate.open()
        _ = try await collector.awaitEmission { $0.map(\.id) == ["perm-2"] }

        #expect(collector.emissions.last?.map(\.id) == ["perm-2"])
    }
}

private final class RestGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if isOpen {
                lock.unlock()
                continuation.resume()
                return
            }
            continuations.append(continuation)
            lock.unlock()
        }
    }

    func open() {
        lock.lock()
        isOpen = true
        let pending = continuations
        continuations.removeAll()
        lock.unlock()
        pending.forEach { $0.resume() }
    }
}

@MainActor
private func poll(
    timeout: Duration = .seconds(3),
    predicate: () -> Bool
) async throws {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if predicate() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    throw GateTimeoutError()
}

private struct GateTimeoutError: Error {}

@MainActor
private final class StatusEmissionCollector {
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
        throw GateTimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

@MainActor
private final class PermissionEmissionCollector {
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
        throw GateTimeoutError()
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

private func testPermissionRequest(_ id: String) -> PermissionRequest {
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

private final class GatedStatusRepository: SessionRepository, @unchecked Sendable {
    var statuses: [SessionStatus]
    var nextGetGate: RestGate?
    private let lock = NSLock()
    private var _statusFetchCount = 0
    private var _completedFetchCount = 0

    var statusFetchCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _statusFetchCount
    }

    var completedFetchCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _completedFetchCount
    }

    init(statuses: [SessionStatus]) {
        self.statuses = statuses
    }

    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        lock.lock()
        _statusFetchCount += 1
        let gate = nextGetGate
        nextGetGate = nil
        let result = statuses
        lock.unlock()
        if let gate {
            await gate.wait()
        }
        lock.lock()
        _completedFetchCount += 1
        lock.unlock()
        return result
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
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

private final class GatedPermissionRepository: SessionRepository, @unchecked Sendable {
    private let script: [[PermissionRequest]]
    var nextGetGate: RestGate?
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
        let gate = nextGetGate
        nextGetGate = nil
        let current = script.isEmpty ? [] : script[min(index, script.count - 1)]
        if index < script.count {
            index += 1
        }
        lock.unlock()
        if let gate {
            await gate.wait()
        }
        return current
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
