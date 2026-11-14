import Foundation
@testable import OpenCode_Mobile

nonisolated final class ServerHealthFakeLiveConnection: LiveConnection, @unchecked Sendable {
    private let lock = NSLock()
    private var current = LiveConnectionSnapshot()
    private var continuations: [UUID: AsyncStream<LiveConnectionSnapshot>.Continuation] = [:]

    var connectionState: EventStreamConnectionState { snapshot.connectionState }

    var snapshot: LiveConnectionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return current
    }

    func snapshotUpdates() -> AsyncStream<LiveConnectionSnapshot> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            let value = current
            lock.unlock()
            continuation.yield(value)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func publish(_ snapshot: LiveConnectionSnapshot) {
        lock.lock()
        current = snapshot
        let subscribers = Array(continuations.values)
        lock.unlock()
        subscribers.forEach { $0.yield(snapshot) }
    }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        AsyncStream { $0.finish() }
    }

    func events() -> AsyncStream<OpencodeEvent> {
        AsyncStream { $0.finish() }
    }

    func start() {}
    func stop() async {}
}

nonisolated final class ServerHealthFakePinger: ServerHealthPinging, @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [ServerHealthPingOutcome]
    private var calls = 0

    init(outcomes: [ServerHealthPingOutcome] = [.success(latencyMs: 20, pid: 1, version: "1.0.0")]) {
        self.outcomes = outcomes
    }

    var callCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return calls
    }

    func ping(_: ServerHealthPingTarget) async -> ServerHealthPingOutcome? {
        lock.lock()
        defer { lock.unlock() }
        calls += 1
        return outcomes.count > 1 ? outcomes.removeFirst() : outcomes.first
    }
}

/// A sleep that waits until the test releases it, or throws when its task is cancelled.
nonisolated final class ServerHealthManualSleeper: @unchecked Sendable {
    private let lock = NSLock()
    private var waiters: [UUID: CheckedContinuation<Void, Error>] = [:]

    var waiting: Int {
        lock.lock()
        defer { lock.unlock() }
        return waiters.count
    }

    func sleep(_: Duration) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                if Task.isCancelled {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                waiters[id] = continuation
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            let continuation = waiters.removeValue(forKey: id)
            lock.unlock()
            continuation?.resume(throwing: CancellationError())
        }
    }

    func releaseAll() {
        lock.lock()
        let pending = Array(waiters.values)
        waiters = [:]
        lock.unlock()
        pending.forEach { $0.resume() }
    }
}

@MainActor
final class ServerHealthFakeMonitor: ServerHealthMonitoring {
    var revision = 0
    var liveStatus = ServerHealthLiveStatus.stopped

    func updates() -> AsyncStream<Void> {
        AsyncStream { continuation in
            continuation.yield()
            continuation.finish()
        }
    }
}

enum ServerHealthTestSupport {
    static func snapshot(
        profileID: String?,
        state: EventStreamConnectionState,
        required: [String] = ["global"],
        live: [String] = []
    ) -> LiveConnectionSnapshot {
        LiveConnectionSnapshot(
            config: profileID.map {
                LiveConnectionConfig(profileID: $0, connectionIdentity: "id-\($0)", serverProtocol: .v1, directories: [])
            },
            connectionState: state,
            requiredTargets: required,
            liveTargets: live
        )
    }

    static func target(_ profileID: String) -> ServerHealthPingTarget {
        ServerHealthPingTarget(
            profileID: profileID,
            connection: ServerConnection(baseURL: URL(string: "http://192.0.2.10:4096")!, password: "test-password"),
            serverProtocol: .v1
        )
    }

    /// Polls `condition` on the main actor until it holds or about two seconds pass.
    @MainActor
    static func eventually(_ condition: @MainActor () async -> Bool) async -> Bool {
        for _ in 0 ..< 200 {
            if await condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return await condition()
    }
}
