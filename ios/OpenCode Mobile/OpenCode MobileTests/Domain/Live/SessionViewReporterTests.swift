import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct SessionViewReporterTests {
    @Test func postsOnceWhenFocusedSessionIsUnread() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        let liveStore = ViewedFakeSessionLiveStore()
        let reporter = makeReporter(repository: repository, liveStore: liveStore)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.markCalls.first?.idle == 100)
        #expect(await viewedWaitUntil { liveStore.applied.count == 1 })
        #expect(liveStore.applied.first?.idle == 100)

        // Reopening the same watermark does not post again.
        reporter.release(sessionId: "ses-1")
        reporter.focus(sessionId: "ses-1")
        try? await Task.sleep(for: .milliseconds(80))
        #expect(repository.markCalls.count == 1)
    }

    @Test func postsServerIdleNotStaleCachedIdle() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 9))
        let liveStore = ViewedFakeSessionLiveStore()
        liveStore.emitKnown(unreadSession(idle: 7))
        let reporter = makeReporter(repository: repository, liveStore: liveStore)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.markCalls.first?.idle == 9)
        #expect(repository.getSessionCount == 1)
    }

    @Test func knownSessionIdleChangeTriggersServerRecheck() async {
        var read = unreadSession(idle: 10)
        read.viewedAtEpochMs = 10
        let repository = ViewedFakeSessionRepository(session: read)
        let liveStore = ViewedFakeSessionLiveStore()
        liveStore.emitKnown(read)
        let reporter = makeReporter(repository: repository, liveStore: liveStore)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })
        #expect(await viewedWaitUntil { liveStore.knownObserverCount == 1 })
        #expect(repository.markCalls.isEmpty)

        // Home's list refetch learns a newer idle first: re-check the server, then post.
        var finished = read
        finished.idleAtEpochMs = 25
        repository.session = finished
        liveStore.emitKnown(finished)
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.markCalls.first?.idle == 25)
        #expect(repository.getSessionCount == 2)
    }

    @Test func doesNotPostWhenRead() async {
        var read = unreadSession(idle: 100)
        read.viewedAtEpochMs = 100
        let repository = ViewedFakeSessionRepository(session: read)
        let reporter = makeReporter(repository: repository)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })
        try? await Task.sleep(for: .milliseconds(60))
        #expect(repository.markCalls.isEmpty)
    }

    @Test func neverTouchesTheNetworkOnV1() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        let eventStream = FakeForegroundEventStream()
        let reporter = makeReporter(repository: repository, eventStream: eventStream, serverProtocol: .v1)

        reporter.focus(sessionId: "ses-1")
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        try? await Task.sleep(for: .milliseconds(100))
        #expect(repository.getSessionCount == 0)
        #expect(repository.markCalls.isEmpty)
    }

    @Test func skipsSubagentChildSessions() async {
        let child = Session(
            id: "ses-1", title: "Child", slug: nil, projectID: nil, directory: "/repo", parentID: "ses-root",
            updatedAtEpochMs: 1, idleAtEpochMs: 100
        )
        let repository = ViewedFakeSessionRepository(session: child)
        let reporter = makeReporter(repository: repository)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })
        try? await Task.sleep(for: .milliseconds(60))
        #expect(repository.markCalls.isEmpty)
    }

    @Test func runFinishingWhileFocusedEndsViewed() async {
        var read = unreadSession(idle: 10)
        read.viewedAtEpochMs = 10
        let repository = ViewedFakeSessionRepository(session: read)
        let eventStream = FakeForegroundEventStream()
        let reporter = makeReporter(repository: repository, eventStream: eventStream)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })
        #expect(repository.markCalls.isEmpty)

        var finished = read
        finished.idleAtEpochMs = 20
        finished.idleOutcome = .succeeded
        repository.session = finished
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))

        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.markCalls.first?.idle == 20)
    }

    @Test func runEndForAnotherSessionIsIgnored() async {
        var read = unreadSession(idle: 10)
        read.viewedAtEpochMs = 10
        let repository = ViewedFakeSessionRepository(session: read)
        let eventStream = FakeForegroundEventStream()
        let reporter = makeReporter(repository: repository, eventStream: eventStream)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-other"))
        try? await Task.sleep(for: .milliseconds(80))
        #expect(repository.getSessionCount == 1)
    }

    @Test func reconnectReevaluatesFocusedSession() async {
        var read = unreadSession(idle: 10)
        read.viewedAtEpochMs = 10
        let repository = ViewedFakeSessionRepository(session: read)
        let eventStream = FakeForegroundEventStream()
        let reporter = makeReporter(repository: repository, eventStream: eventStream)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.getSessionCount == 1 })

        repository.session = unreadSession(idle: 30)
        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.markCalls.first?.idle == 30)
    }

    @Test func retriesFailuresWithDoublingBackoffThenResets() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.failuresRemaining = 3
        let sleeps = SleepRecorder()
        let reporter = makeReporter(repository: repository, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 4 })
        #expect(sleeps.delays == [250, 500, 1_000])
        #expect(repository.markCalls.allSatisfy { $0.idle == 100 })

        // Backoff resets after success: the next failure waits 250 ms again.
        repository.failuresRemaining = 1
        repository.session = unreadSession(idle: 200)
        reporter.release(sessionId: "ses-1")
        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 6 })
        #expect(sleeps.delays == [250, 500, 1_000, 250])
    }

    @Test func retryBudgetRunsOutThenReconnectRetries() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.failuresRemaining = .max
        let eventStream = FakeForegroundEventStream()
        let sleeps = SleepRecorder()
        let reporter = makeReporter(repository: repository, eventStream: eventStream, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        // First attempt plus five bounded retries, then it waits for a trigger.
        #expect(await viewedWaitUntil { repository.markCalls.count == 6 })
        #expect(sleeps.delays == [250, 500, 1_000, 2_000, 4_000])
        try? await Task.sleep(for: .milliseconds(150))
        #expect(repository.markCalls.count == 6)

        eventStream.emit(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        #expect(await viewedWaitUntil { repository.markCalls.count == 12 })
        #expect(sleeps.delays == [250, 500, 1_000, 2_000, 4_000, 250, 500, 1_000, 2_000, 4_000])
    }

    @Test func fetchFailuresShareTheRetryBudget() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.getSessionFailuresRemaining = 2
        let sleeps = SleepRecorder()
        let reporter = makeReporter(repository: repository, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        #expect(repository.getSessionCount == 3)
        #expect(sleeps.delays == [250, 500])
    }

    @Test func stopsRetryingAfterRelease() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.failuresRemaining = .max
        let sleeps = SleepRecorder(realDelayMs: 30)
        let reporter = makeReporter(repository: repository, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count >= 1 })
        reporter.release(sessionId: "ses-1")
        let attempts = repository.markCalls.count
        try? await Task.sleep(for: .milliseconds(200))
        #expect(repository.markCalls.count == attempts)
    }

    @Test func clearCacheDropsFocus() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.failuresRemaining = .max
        let sleeps = SleepRecorder(realDelayMs: 30)
        let reporter = makeReporter(repository: repository, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count >= 1 })
        reporter.clearCache()
        let attempts = repository.markCalls.count
        try? await Task.sleep(for: .milliseconds(200))
        #expect(repository.markCalls.count == attempts)
    }

    @Test func notFoundStopsWithoutRetry() async {
        let repository = ViewedFakeSessionRepository(session: unreadSession(idle: 100))
        repository.markError = SessionError.http(statusCode: 404, message: nil)
        let sleeps = SleepRecorder()
        let reporter = makeReporter(repository: repository, sleeps: sleeps)

        reporter.focus(sessionId: "ses-1")
        #expect(await viewedWaitUntil { repository.markCalls.count == 1 })
        try? await Task.sleep(for: .milliseconds(60))
        #expect(repository.markCalls.count == 1)
        #expect(sleeps.delays.isEmpty)
    }

    private func makeReporter(
        repository: ViewedFakeSessionRepository,
        liveStore: ViewedFakeSessionLiveStore? = nil,
        eventStream: FakeForegroundEventStream = FakeForegroundEventStream(),
        serverProtocol: ServerProtocol = .v2,
        sleeps: SleepRecorder = SleepRecorder()
    ) -> DefaultSessionViewReporter {
        DefaultSessionViewReporter(
            sessionRepository: repository,
            sessionLiveStore: liveStore ?? ViewedFakeSessionLiveStore(),
            eventStream: eventStream,
            capabilities: { .forProtocol(serverProtocol) },
            sleep: { delay in try await sleeps.sleep(delay) }
        )
    }

    private func unreadSession(idle: Int64) -> Session {
        Session(
            id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil,
            updatedAtEpochMs: 1, idleAtEpochMs: idle
        )
    }
}

@MainActor
struct SessionLiveStoreViewedTests {
    @Test func sessionViewedPatchesMonotonicallyWithoutRefetch() async {
        let repository = ViewedFakeSessionRepository(session: listedSession(idle: 100, viewed: nil))
        let eventStream = FakeForegroundEventStream()
        let store = makeStore(repository: repository, eventStream: eventStream)
        let collector = SnapshotCollector(stream: store.observeSessions(directory: "/repo"))
        defer { collector.cancel() }

        #expect(await viewedWaitUntil { collector.latest?.first?.isServerUnread == true })
        // Sync coordinator + the session loop must both listen before events are emitted.
        #expect(await viewedWaitUntil { eventStream.eventSubscriberCount >= 2 })
        let listCalls = repository.listSessionsCount

        eventStream.emit(viewedEvent(idle: 100))
        #expect(await viewedWaitUntil { collector.latest?.first?.viewedAtEpochMs == 100 })
        #expect(collector.latest?.first?.isServerUnread == false)
        let known = SnapshotCollectorOne(stream: store.observeKnownSession(sessionId: "ses-1"))
        defer { known.cancel() }
        #expect(await viewedWaitUntil { known.latest?.viewedAtEpochMs == 100 })

        // An older watermark never moves viewed backwards.
        eventStream.emit(viewedEvent(idle: 40))
        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs + 200))
        #expect(collector.latest?.first?.viewedAtEpochMs == 100)
        #expect(repository.listSessionsCount == listCalls)
    }

    @Test func restListRacingViewedKeepsWatermark() async {
        let repository = ViewedFakeSessionRepository(session: listedSession(idle: 100, viewed: nil))
        let eventStream = FakeForegroundEventStream()
        let store = makeStore(repository: repository, eventStream: eventStream)
        let collector = SnapshotCollector(stream: store.observeSessions(directory: "/repo"))
        defer { collector.cancel() }
        #expect(await viewedWaitUntil { collector.latest?.first?.isServerUnread == true })
        // Sync coordinator + the session loop must both listen before events are emitted.
        #expect(await viewedWaitUntil { eventStream.eventSubscriberCount >= 2 })

        store.applySessionViewed(sessionId: "ses-1", idleAtEpochMs: 100)
        #expect(await viewedWaitUntil { collector.latest?.first?.isServerUnread == false })

        // The server list still reports the stale watermark (it raced the view).
        let listCalls = repository.listSessionsCount
        eventStream.emit(OpencodeEvent(type: "session.updated", sessionID: "ses-1"))
        #expect(await viewedWaitUntil(timeoutMs: 3_000) { repository.listSessionsCount > listCalls })
        try? await Task.sleep(for: .milliseconds(100))
        #expect(collector.latest?.first?.viewedAtEpochMs == 100)
        #expect(collector.latest?.first?.isServerUnread == false)
    }

    @Test func v2RunEndRefetchesListForIdleAndOutcome() async {
        let repository = ViewedFakeSessionRepository(session: listedSession(idle: nil, viewed: nil))
        let eventStream = FakeForegroundEventStream()
        let store = makeStore(repository: repository, eventStream: eventStream, serverProtocol: .v2)
        let collector = SnapshotCollector(stream: store.observeSessions(directory: "/repo"))
        defer { collector.cancel() }
        #expect(await viewedWaitUntil { collector.latest?.count == 1 })
        #expect(await viewedWaitUntil { eventStream.eventSubscriberCount >= 2 })
        let listCalls = repository.listSessionsCount

        let known = SnapshotCollectorOne(stream: store.observeKnownSession(sessionId: "ses-1"))
        defer { known.cancel() }
        #expect(await viewedWaitUntil { known.latest?.idleAtEpochMs == nil && known.latest != nil })

        repository.session = listedSession(idle: 200, viewed: nil, outcome: .failed)
        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        #expect(await viewedWaitUntil(timeoutMs: 3_000) { collector.latest?.first?.idleOutcome == .failed })
        #expect(repository.listSessionsCount > listCalls)
        // The reporter's re-check trigger sees the new watermark too.
        #expect(await viewedWaitUntil { known.latest?.idleAtEpochMs == 200 })
    }

    @Test func v1RunEndDoesNotRefetchList() async {
        let repository = ViewedFakeSessionRepository(session: listedSession(idle: nil, viewed: nil))
        let eventStream = FakeForegroundEventStream()
        let store = makeStore(repository: repository, eventStream: eventStream, serverProtocol: .v1)
        let collector = SnapshotCollector(stream: store.observeSessions(directory: "/repo"))
        defer { collector.cancel() }
        #expect(await viewedWaitUntil { collector.latest?.count == 1 })
        #expect(await viewedWaitUntil { eventStream.eventSubscriberCount >= 2 })
        let listCalls = repository.listSessionsCount

        eventStream.emit(OpencodeEvent(type: "session.idle", sessionID: "ses-1"))
        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs + 300))
        #expect(repository.listSessionsCount == listCalls)
    }

    private func makeStore(
        repository: ViewedFakeSessionRepository,
        eventStream: FakeForegroundEventStream,
        serverProtocol: ServerProtocol = .v2
    ) -> DefaultSessionLiveStore {
        DefaultSessionLiveStore(
            sessionRepository: repository,
            eventStream: eventStream,
            sessionCache: NoOpSessionCache.shared,
            capabilities: { .forProtocol(serverProtocol) }
        )
    }

    private func listedSession(idle: Int64?, viewed: Int64?, outcome: SessionIdleOutcome? = nil) -> Session {
        Session(
            id: "ses-1", title: "Alpha", slug: nil, projectID: nil, directory: "/repo", parentID: nil,
            updatedAtEpochMs: 1, idleAtEpochMs: idle, viewedAtEpochMs: viewed, idleOutcome: outcome
        )
    }

    private func viewedEvent(idle: Int64) -> OpencodeEvent {
        OpencodeEvent(
            type: "session.viewed",
            sessionID: "ses-1",
            rawProperties: ["sessionID": .string("ses-1"), "idle": .int64(idle)]
        )
    }
}

// MARK: - Helpers

@MainActor
private func viewedWaitUntil(
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

@MainActor
private final class SnapshotCollector {
    private(set) var latest: [Session]?
    private var task: Task<Void, Never>?

    init(stream: AsyncStream<SessionListSnapshot>) {
        task = Task { @MainActor [weak self] in
            for await snapshot in stream {
                self?.latest = snapshot.sessions
            }
        }
    }

    func cancel() {
        task?.cancel()
    }
}

@MainActor
private final class SnapshotCollectorOne {
    private(set) var latest: Session?
    private var task: Task<Void, Never>?

    init(stream: AsyncStream<Session>) {
        task = Task { @MainActor [weak self] in
            for await session in stream {
                self?.latest = session
            }
        }
    }

    func cancel() {
        task?.cancel()
    }
}

private final class SleepRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _delays: [Int] = []
    private let realDelayMs: Int

    init(realDelayMs: Int = 0) {
        self.realDelayMs = realDelayMs
    }

    var delays: [Int] {
        lock.lock()
        defer { lock.unlock() }
        return _delays
    }

    func sleep(_ delayMs: Int) async throws {
        lock.lock()
        _delays.append(delayMs)
        lock.unlock()
        if realDelayMs > 0 {
            try await Task.sleep(for: .milliseconds(realDelayMs))
        } else {
            await Task.yield()
            try Task.checkCancellation()
        }
    }
}

@MainActor
private final class ViewedFakeSessionLiveStore: SessionLiveStore {
    private var known: Session?
    private var continuations: [UUID: AsyncStream<Session>.Continuation] = [:]
    private(set) var applied: [(id: String, idle: Int64)] = []

    var knownObserverCount: Int { continuations.count }

    func emitKnown(_ session: Session) {
        known = session
        continuations.values.forEach { $0.yield(session) }
    }

    func observeSessions(directory _: String) -> AsyncStream<SessionListSnapshot> {
        AsyncStream { $0.finish() }
    }

    func clearCache() {}

    func observeKnownSession(sessionId: String) -> AsyncStream<Session> {
        AsyncStream { continuation in
            let id = UUID()
            continuations[id] = continuation
            if let known, known.id == sessionId {
                continuation.yield(known)
            }
            continuation.onTermination = { _ in
                Task { @MainActor in self.continuations.removeValue(forKey: id) }
            }
        }
    }

    func applySessionViewed(sessionId: String, idleAtEpochMs: Int64) {
        applied.append((sessionId, idleAtEpochMs))
        if let known, known.id == sessionId {
            emitKnown(known.withViewed(idleAtEpochMs: idleAtEpochMs))
        }
    }
}

private final class ViewedFakeSessionRepository: SessionRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var _session: Session
    private var _markCalls: [(id: String, idle: Int64)] = []
    private var _getSessionCount = 0
    private var _listSessionsCount = 0
    private var _failuresRemaining = 0
    private var _getSessionFailuresRemaining = 0
    private var _markError: Error?

    init(session: Session) {
        _session = session
    }

    var session: Session {
        get { locked { _session } }
        set { locked { _session = newValue } }
    }

    var markCalls: [(id: String, idle: Int64)] { locked { _markCalls } }
    var getSessionCount: Int { locked { _getSessionCount } }
    var listSessionsCount: Int { locked { _listSessionsCount } }
    var failuresRemaining: Int {
        get { locked { _failuresRemaining } }
        set { locked { _failuresRemaining = newValue } }
    }
    var getSessionFailuresRemaining: Int {
        get { locked { _getSessionFailuresRemaining } }
        set { locked { _getSessionFailuresRemaining = newValue } }
    }
    var markError: Error? {
        get { locked { _markError } }
        set { locked { _markError = newValue } }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func markSessionViewed(sessionId: String, idleAtEpochMs: Int64) async throws {
        let failure: Error? = locked {
            _markCalls.append((sessionId, idleAtEpochMs))
            if let _markError { return _markError }
            if _failuresRemaining > 0 {
                _failuresRemaining -= 1
                return SessionError.transport("offline")
            }
            return nil
        }
        if let failure { throw failure }
    }

    func listSessions(directory _: String?) async throws -> [Session] {
        locked {
            _listSessionsCount += 1
            return [_session]
        }
    }

    func getSession(id _: String, directory _: String?) async throws -> Session {
        let result: Result<Session, Error> = locked {
            _getSessionCount += 1
            if _getSessionFailuresRemaining > 0 {
                _getSessionFailuresRemaining -= 1
                return .failure(SessionError.transport("offline"))
            }
            return .success(_session)
        }
        return try result.get()
    }

    func createSession(parentID _: String?, title _: String?, directory _: String?) async throws -> Session { fatalError() }
    func getAllSessionStatuses(directory _: String?) async throws -> [SessionStatus] { [] }
    func updateSession(id _: String, title _: String?, directory _: String?) async throws -> Session { fatalError() }
    func archiveSession(id _: String, directory _: String?) async throws -> Session { fatalError() }
    func deleteSession(id _: String, directory _: String?) async throws -> Bool { fatalError() }
    func listChildSessions(id _: String) async throws -> [Session] { [] }
    func listSessionTodos(id _: String, directory _: String?) async throws -> [SessionTodo] { [] }
    func initializeSessionAgents(id _: String, messageID _: String, providerID _: String, modelID _: String) async throws -> Bool { fatalError() }
    func forkSession(id _: String, atMessageID _: String?) async throws -> Session { fatalError() }
    func abortSession(id _: String, directory _: String?) async throws -> Bool { fatalError() }
    func shareSession(id _: String) async throws -> Session { fatalError() }
    func unshareSession(id _: String) async throws -> Session { fatalError() }
    func getSessionDiff(id _: String, messageID _: String?, directory _: String?) async throws -> [FileDiff] { [] }
    func summarizeSession(id _: String, providerID _: String, modelID _: String) async throws -> Bool { fatalError() }
    func revertSession(id _: String, messageID _: String, partID _: String?) async throws -> Bool { fatalError() }
    func unrevertSession(id _: String) async throws -> Bool { fatalError() }
    func respondToPermission(
        sessionID _: String,
        permissionID _: String,
        decision _: PermissionDecision,
        directory _: String?
    ) async throws -> Bool { fatalError() }
}
