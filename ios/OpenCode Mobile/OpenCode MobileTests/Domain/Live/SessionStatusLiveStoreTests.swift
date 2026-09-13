import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionStatusLiveStoreTests {
    @Test func observeActivity_absentBeforeSeedThenServerRunning() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        let seeded = try await collector.awaitEmission { $0.seeded }
        #expect(seeded.entries.isEmpty)
        #expect(seeded.statusLabel(for: "ses-1") == nil)

        eventStream.emit(busyStatusEvent("ses-1"))
        let running = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }
        collector.cancel()

        #expect(running.seeded)
        #expect(running.entries["ses-1"]?.source == .server)
        #expect(running.statusLabel(for: "ses-1") == "Working")
    }

    @Test func observeActivity_appliesStatusEventLocallyWithoutWaitingForGet() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }
        let fetchesAfterBootstrap = repository.statusFetchCount

        eventStream.emit(busyStatusEvent("ses-1"))
        let snapshot = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }
        collector.cancel()

        #expect(snapshot.entries["ses-1"]?.activity == .running)
        #expect(repository.statusFetchCount == fetchesAfterBootstrap)
    }

    @Test func observeActivity_stepEndedDoesNotFetchRest() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }
        let fetchesAfterBootstrap = repository.statusFetchCount

        eventStream.emit(OpencodeEvent(type: "session.active.snapshot", sessionID: "ses-1"))
        eventStream.emit(
            OpencodeEvent(
                type: "message.updated",
                sessionID: "ses-1",
                rawProperties: ["info": .dictionary(["id": .string("msg-2"), "role": .string("assistant")])]
            )
        )
        try await Task.sleep(for: .milliseconds(80))
        collector.cancel()

        #expect(repository.statusFetchCount == fetchesAfterBootstrap)
        #expect(collector.emissions.last?.entries["ses-1"]?.activity == .running)
    }

    @Test func observeActivity_removesSessionOnIdleEvent() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let snapshot = try await collector.awaitEmission { $0.entries["ses-1"] == nil }
        collector.cancel()

        #expect(snapshot.entries.isEmpty)
        #expect(snapshot.seeded)
        #expect(snapshot.statusLabel(for: "ses-1") == nil)
    }

    @Test func observeActivity_keepsPriorWhenStatusLoadFails() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }
        let emissionsBeforeReconnect = collector.emissions.count

        repository.shouldFailStatuses = true
        eventStream.emit(OpencodeEvent(type: "server.connected", sessionID: nil))
        try await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs + 80))
        collector.cancel()

        #expect(collector.emissions.count == emissionsBeforeReconnect)
        #expect(collector.emissions.last?.entries["ses-1"]?.activity == .running)
    }

    @Test func setOptimistic_emitsRunningWithoutRestFetch() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }
        let fetchesAfterBootstrap = repository.statusFetchCount

        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running)
        let snapshot = try await collector.awaitEmission {
            $0.entries["ses-1"]?.source == .optimistic && $0.entries["ses-1"]?.activity == .running
        }
        collector.cancel()

        #expect(snapshot.entries["ses-1"]?.activity == .running)
        #expect(repository.statusFetchCount == fetchesAfterBootstrap)
    }

    @Test func setOptimistic_ttlExpiryRevertsToLastServer() async throws {
        let clock = ControllableSyncClock(now: 1_000)
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            clock: clock
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }

        store.setOptimistic(
            directory: "/repo",
            sessionID: "ses-1",
            activity: .idle,
            ttlMs: 3_000
        )
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .optimistic }

        try await clock.awaitWaiter(milliseconds: 3_000)
        clock.release(milliseconds: 3_000)
        let reverted = try await collector.awaitEmission {
            $0.entries["ses-1"]?.source == .server && $0.entries["ses-1"]?.activity == .running
        }
        collector.cancel()

        #expect(reverted.entries["ses-1"]?.activity == .running)
    }

    @Test func setOptimistic_ttlExpiryRemovesWhenNoServerValue() async throws {
        let clock = ControllableSyncClock(now: 1_000)
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            clock: clock
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }

        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running, ttlMs: 10_000)
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .optimistic }

        try await clock.awaitWaiter(milliseconds: 10_000)
        clock.release(milliseconds: 10_000)
        let expired = try await collector.awaitEmission { $0.entries["ses-1"] == nil }
        collector.cancel()

        #expect(expired.entries["ses-1"] == nil)
    }

    @Test func setOptimistic_serverIdleWinsImmediately() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }

        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running)
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .optimistic }

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let snapshot = try await collector.awaitEmission { $0.entries["ses-1"] == nil }
        collector.cancel()

        #expect(snapshot.entries.isEmpty)
    }

    @Test func loaderSnapshot_keepsYoungOptimisticAndDropsStaleServer() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [
                SessionStatus(sessionID: "ses-old", raw: ["type": "busy"]),
                SessionStatus(sessionID: "ses-keep", raw: ["type": "busy"]),
            ]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission {
            $0.entries["ses-old"]?.activity == .running && $0.entries["ses-keep"]?.activity == .running
        }

        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running)
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .optimistic }

        repository.statuses = [SessionStatus(sessionID: "ses-keep", raw: ["type": "retry"])]
        await store.invalidate(directory: "/repo")
        let snapshot = try await collector.awaitEmission { activity in
            activity.entries["ses-old"] == nil
                && activity.entries["ses-keep"]?.activity.isBusy == true
                && activity.entries["ses-1"]?.source == .optimistic
        }
        collector.cancel()

        #expect(snapshot.entries["ses-old"] == nil)
        #expect(snapshot.entries["ses-keep"]?.source == .server)
        if case let .retrying(attempt, _, _) = snapshot.entries["ses-keep"]?.activity {
            #expect(attempt == nil)
        } else {
            Issue.record("expected retrying")
        }
        #expect(snapshot.entries["ses-1"]?.source == .optimistic)
    }

    @Test func runningReconcile_reloadsOnlyWhileBusyAndCatchUp() async throws {
        let clock = ControllableSyncClock(now: 1_000)
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let world = FakeWorldSignals()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            worldSignals: world,
            clock: clock
        )

        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }
        try await clock.awaitWaiter(milliseconds: SessionActivityPolicy.runningReconcileMs)
        let fetchesWhileIdle = repository.statusFetchCount
        clock.release(milliseconds: SessionActivityPolicy.runningReconcileMs)
        try await Task.sleep(for: .milliseconds(40))
        #expect(repository.statusFetchCount == fetchesWhileIdle)

        eventStream.emit(busyStatusEvent("ses-1"))
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.activity == .running }
        try await clock.awaitWaiter(milliseconds: SessionActivityPolicy.runningReconcileMs)
        let fetchesBeforeBusyTick = repository.statusFetchCount
        clock.release(milliseconds: SessionActivityPolicy.runningReconcileMs)
        let reloaded = await awaitPredicate {
            repository.statusFetchCount > fetchesBeforeBusyTick
        }
        #expect(reloaded)

        world.setOccupancy(.stopped)
        try await clock.awaitWaiter(milliseconds: SessionActivityPolicy.runningReconcileMs)
        let fetchesWhileBackground = repository.statusFetchCount
        clock.release(milliseconds: SessionActivityPolicy.runningReconcileMs)
        try await Task.sleep(for: .milliseconds(40))
        collector.cancel()
        #expect(repository.statusFetchCount == fetchesWhileBackground)
    }

    @Test func parsesV1AndV2RetryPayloads() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }

        eventStream.emit(
            OpencodeEvent(
                type: "session.status",
                sessionID: "ses-1",
                rawProperties: [
                    "sessionID": .string("ses-1"),
                    "status": .dictionary([
                        "type": .string("retry"),
                        "attempt": .int(2),
                        "nextAtMs": .int64(9_000),
                        "message": .string("model overloaded"),
                    ]),
                ]
            )
        )
        let retrying = try await collector.awaitEmission {
            if case .retrying = $0.entries["ses-1"]?.activity { return true }
            return false
        }
        collector.cancel()

        if case let .retrying(attempt, nextAtMs, message) = retrying.entries["ses-1"]?.activity {
            #expect(attempt == 2)
            #expect(nextAtMs == 9_000)
            #expect(message == "model overloaded")
        } else {
            Issue.record("expected retrying activity")
        }
    }

    @Test func v1CompletedAssistantMessageIdlesOptimisticRunning() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )
        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.seeded }
        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running)
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .optimistic }

        eventStream.emit(
            OpencodeEvent(
                type: "message.updated",
                sessionID: "ses-1",
                rawProperties: [
                    "info": .dictionary([
                        "id": .string("msg-2"),
                        "role": .string("assistant"),
                        "time": .dictionary(["completed": .int64(20)]),
                    ]),
                ]
            )
        )
        let idle = try await collector.awaitEmission { $0.entries["ses-1"] == nil }
        collector.cancel()
        #expect(idle.entries["ses-1"] == nil)
    }

    @Test func setOptimistic_beforeObserve_appearsOnSubscribe() async throws {
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream
        )

        store.setOptimistic(directory: "/repo", sessionID: "ses-1", activity: .running)
        let collector = ActivityCollector(store: store, directory: "/repo")
        let snapshot = try await collector.awaitEmission {
            $0.entries["ses-1"]?.activity == .running
        }
        collector.cancel()

        #expect(snapshot.entries["ses-1"]?.source == .optimistic)
    }

    @Test func observeActivity_cacheSeedIsFromCacheUntilServerLoader() async throws {
        let cache = RecordingActivityCache()
        cache.activity = [
            SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1_000),
        ]
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-1", raw: ["type": "busy"])]
        )
        repository.holdNextStatuses()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: cache
        )
        let collector = ActivityCollector(store: store, directory: "/repo")
        let cached = try await collector.awaitEmission {
            $0.fromCache && !$0.seeded && $0.entries["ses-1"]?.source == .cached
        }
        #expect(cached.statusLabel(for: "ses-1") == "Working")
        repository.resumeStatuses()
        let server = try await collector.awaitEmission {
            $0.seeded && $0.entries["ses-1"]?.source == .server
        }
        collector.cancel()
        #expect(!server.fromCache)
        let remembered = await awaitPredicate { !cache.rememberCalls.isEmpty }
        #expect(remembered)
    }

    @Test func observeActivity_serverSnapshotDropsCachedSessionsAbsentFromServer() async throws {
        let cache = RecordingActivityCache()
        cache.activity = [
            SessionActivityCacheEntry(sessionId: "ses-gone", activity: .running, observedAtMs: 1_000),
            SessionActivityCacheEntry(sessionId: "ses-keep", activity: .running, observedAtMs: 1_000),
        ]
        let repository = StatusLiveStoreFakeSessionRepository(
            statuses: [SessionStatus(sessionID: "ses-keep", raw: ["type": "retry", "attempt": "3"])]
        )
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: cache
        )
        let collector = ActivityCollector(store: store, directory: "/repo")
        let snapshot = try await collector.awaitEmission {
            $0.seeded && $0.entries["ses-keep"] != nil
        }
        collector.cancel()
        #expect(snapshot.entries["ses-gone"] == nil)
        #expect(snapshot.entries["ses-keep"]?.source == .server)
        if case let .retrying(attempt, _, _) = snapshot.entries["ses-keep"]?.activity {
            #expect(attempt == 3)
        } else {
            Issue.record("expected retrying")
        }
    }

    @Test func observeActivity_sseReplacesCachedEntryForThatSession() async throws {
        let cache = RecordingActivityCache()
        cache.activity = [
            SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1_000),
        ]
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        repository.holdNextStatuses()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: cache
        )
        let collector = ActivityCollector(store: store, directory: "/repo")
        _ = try await collector.awaitEmission { $0.entries["ses-1"]?.source == .cached }
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        let snapshot = try await collector.awaitEmission { $0.entries["ses-1"] == nil }
        collector.cancel()
        repository.resumeStatuses()
        #expect(snapshot.entries["ses-1"] == nil)
        let rememberedDrop = await awaitPredicate {
            cache.rememberCalls.contains { entries in
                entries.allSatisfy { $0.sessionId != "ses-1" }
            }
        }
        #expect(rememberedDrop)
    }

    @Test func observeActivity_clearCacheReseedsPersistedActivity() async throws {
        let cache = RecordingActivityCache()
        cache.activity = [
            SessionActivityCacheEntry(sessionId: "ses-1", activity: .running, observedAtMs: 1_000),
        ]
        let repository = StatusLiveStoreFakeSessionRepository(statuses: [])
        repository.holdNextStatuses()
        let eventStream = FakeForegroundEventStream()
        let store = DefaultSessionStatusLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: cache
        )
        let first = ActivityCollector(store: store, directory: "/repo")
        _ = try await first.awaitEmission { $0.fromCache && $0.entries["ses-1"]?.source == .cached }
        first.cancel()
        store.clearCache()
        repository.holdNextStatuses()
        let second = ActivityCollector(store: store, directory: "/repo")
        let reseeded = try await second.awaitEmission {
            $0.fromCache && !$0.seeded && $0.entries["ses-1"]?.source == .cached
        }
        second.cancel()
        repository.resumeStatuses()
        #expect(reseeded.statusLabel(for: "ses-1") == "Working")
    }
}

@MainActor
private final class ActivityCollector {
    private(set) var emissions: [SessionActivitySnapshot] = []
    private var task: Task<Void, Never>?

    init(store: DefaultSessionStatusLiveStore, directory: String) {
        task = Task {
            for await snapshot in store.observeActivity(directory: directory) {
                emissions.append(snapshot)
            }
        }
    }

    func awaitEmission(
        timeout: Duration = .seconds(3),
        matching predicate: @escaping (SessionActivitySnapshot) -> Bool
    ) async throws -> SessionActivitySnapshot {
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

private func busyStatusEvent(_ sessionID: String) -> OpencodeEvent {
    OpencodeEvent(
        type: "session.status",
        sessionID: sessionID,
        rawProperties: [
            "sessionID": .string(sessionID),
            "status": .dictionary(["type": .string("busy")]),
        ]
    )
}

private final class ControllableSyncClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    var now: Int64
    private var waiters: [Int: [CheckedContinuation<Void, Never>]] = [:]

    init(now: Int64 = 1_000) {
        self.now = now
    }

    func nowMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return now
    }

    func sleep(milliseconds: Int) async {
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                waiters[milliseconds, default: []].append(continuation)
                lock.unlock()
            }
        } onCancel: {
            lock.lock()
            if var bucket = waiters[milliseconds], !bucket.isEmpty {
                let continuation = bucket.removeFirst()
                waiters[milliseconds] = bucket
                lock.unlock()
                continuation.resume()
                return
            }
            lock.unlock()
        }
    }

    func release(milliseconds: Int) {
        lock.lock()
        let pending = waiters.removeValue(forKey: milliseconds) ?? []
        lock.unlock()
        pending.forEach { $0.resume() }
    }

    func awaitWaiter(milliseconds: Int, timeout: Duration = .seconds(3)) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            lock.lock()
            let count = waiters[milliseconds]?.count ?? 0
            lock.unlock()
            if count > 0 { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw TimeoutError()
    }
}

private func awaitPredicate(timeout: Duration = .seconds(3), _ predicate: () -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if predicate() { return true }
        try? await Task.sleep(for: .milliseconds(20))
    }
    return false
}

private final class StatusLiveStoreFakeSessionRepository: SessionRepository, @unchecked Sendable {
    var statuses: [SessionStatus]
    var statusFetchCount = 0
    var shouldFailStatuses = false
    private let lock = NSLock()
    private var holdContinuation: CheckedContinuation<Void, Never>?
    private var holdNext = false
    private var holdReleased = false

    init(statuses: [SessionStatus]) {
        self.statuses = statuses
    }

    func holdNextStatuses() {
        lock.lock()
        holdNext = true
        holdReleased = false
        lock.unlock()
    }

    func resumeStatuses() {
        lock.lock()
        holdNext = false
        holdReleased = true
        let continuation = holdContinuation
        holdContinuation = nil
        lock.unlock()
        continuation?.resume()
    }

    func listSessions(directory: String?) async throws -> [Session] { [] }
    func createSession(parentID: String?, title: String?, directory: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory: String?) async throws -> [SessionStatus] {
        statusFetchCount += 1
        if shouldFailStatuses { throw SessionError.http(statusCode: 500, message: "status failed") }
        let shouldHold: Bool
        lock.lock()
        shouldHold = holdNext
        if shouldHold {
            holdNext = false
        }
        lock.unlock()
        if shouldHold {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                lock.lock()
                if holdReleased {
                    lock.unlock()
                    continuation.resume()
                    return
                }
                holdContinuation = continuation
                lock.unlock()
            }
        }
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

private final class RecordingActivityCache: SessionCache, @unchecked Sendable {
    var activity: [SessionActivityCacheEntry]?
    private let lock = NSLock()
    private var _rememberCalls: [[SessionActivityCacheEntry]] = []

    var rememberCalls: [[SessionActivityCacheEntry]] {
        lock.lock()
        defer { lock.unlock() }
        return _rememberCalls
    }

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]? { activity }
    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async {
        lock.lock()
        _rememberCalls.append(entries)
        lock.unlock()
    }
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        NoOpSessionCache.shared.openMessages(sessionID: sessionID, worktree: worktree)
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}
