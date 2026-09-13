import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let eventAdapter: EventDomainAdapter
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let sessionCache: any SessionCache
    private let clock: any SyncClock
    private let connection: any LiveConnection
    private let invalidationAckTimeoutMs: Int
    private let activeDirectories = ActiveDirectorySet()
    private let optimistic = OptimisticActivityTable()
    private let mutationBus = ActivityMutationBus()
    private var cache: [String: SharedLiveObservation<SessionActivitySnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter = EventDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil,
        sessionCache: any SessionCache = NoOpSessionCache.shared,
        clock: any SyncClock = SystemSyncClock(),
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.eventAdapter = eventAdapter
        self.worldSignals = worldSignals
        self.clock = clock
        self.sessionCache = sessionCache
        self.connection = eventStream.asLiveConnection()
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
        self.invalidationAckTimeoutMs = invalidationAckTimeoutMs
    }

    func observeActivity(directory: String) -> AsyncStream<SessionActivitySnapshot> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) async {
        let keys: [ProjectionKey]
        if let directory {
            keys = activeDirectories.contains(directory) ? [.sessionStatuses(directory: directory)] : []
        } else {
            keys = activeDirectories.snapshot().map { .sessionStatuses(directory: $0) }
        }
        let coordinator = syncCoordinator
        let timeoutMs = invalidationAckTimeoutMs
        await withTaskGroup(of: Void.self) { group in
            for key in keys {
                group.addTask {
                    coordinator.invalidate(key)
                    await awaitWithTimeout(milliseconds: timeoutMs) {
                        await coordinator.ensureFresh(key)
                    }
                }
            }
        }
    }

    func setOptimistic(
        directory: String,
        sessionID: String,
        activity: SessionActivity,
        ttlMs: Int64
    ) {
        let now = clock.nowMs()
        optimistic.set(
            directory: directory,
            sessionID: sessionID,
            activity: activity,
            observedAtMs: now,
            expiresAtMs: now + max(ttlMs, 0)
        )
        mutationBus.yield(
            .setOptimistic(
                directory: directory,
                sessionID: sessionID,
                ttlMs: max(ttlMs, 0),
                observedAtMs: now
            )
        )
    }

    func clearCache() {
        let stale = Array(cache.values)
        cache.removeAll()
        optimistic.removeAll()
        stale.forEach { $0.finish() }
    }

    private func observation(for directory: String) -> SharedLiveObservation<SessionActivitySnapshot> {
        if let existing = cache[directory], !existing.isTerminated {
            return existing
        }
        let sessionRepository = sessionRepository
        let eventStream = eventStream
        let eventAdapter = eventAdapter
        let worldSignals = worldSignals
        let syncCoordinator = syncCoordinator
        let sessionCache = sessionCache
        let clock = clock
        let connection = connection
        let activeDirectories = activeDirectories
        let optimistic = optimistic
        let mutationBus = mutationBus
        let observation = SharedLiveObservation<SessionActivitySnapshot> { yield in
            Task.detached {
                await Self.runActivityLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    eventAdapter: eventAdapter,
                    worldSignals: worldSignals,
                    syncCoordinator: syncCoordinator,
                    sessionCache: sessionCache,
                    clock: clock,
                    connection: connection,
                    activeDirectories: activeDirectories,
                    optimistic: optimistic,
                    mutationBus: mutationBus,
                    yield: yield
                )
            }
        }
        cache[directory] = observation
        return observation
    }

    private nonisolated static func runActivityLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter,
        worldSignals: any WorldSignals,
        syncCoordinator: any SyncCoordinator,
        sessionCache: any SessionCache,
        clock: any SyncClock,
        connection: any LiveConnection,
        activeDirectories: ActiveDirectorySet,
        optimistic: OptimisticActivityTable,
        mutationBus: ActivityMutationBus,
        yield: @escaping @Sendable (SessionActivitySnapshot) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let serverBox = ActivityServerBox()
        let currentBox = ActivitySnapshotBox()
        let gate = LiveStoreRefreshGate()
        let key = ProjectionKey.sessionStatuses(directory: directory)
        let expiryTasks = ExpiryTaskBag()
        let reconcileFlag = ReconcileFlagBox()
        let idleUnconfirmed = IdleUnconfirmedBox()
        let touched = TouchedSessionsBox()
        let idleConfirmTask = IdleConfirmTaskBox()

        @Sendable func persistServer() {
            let entries = serverBox.snapshot().map { sessionID, entry in
                SessionActivityCacheEntry(
                    sessionId: sessionID,
                    activity: entry.activity,
                    observedAtMs: entry.observedAtMs
                )
            }
            Task {
                await sessionCache.rememberActivity(directory: directory, entries: entries)
            }
        }

        @Sendable func publish() {
            optimistic.removeExpired(directory: directory, now: clock.nowMs())
            let next = overlay(
                server: serverBox.snapshot(),
                optimistic: optimistic.snapshot(directory: directory),
                seeded: serverBox.seeded,
                fromCache: serverBox.fromCache
            )
            guard next != currentBox.snapshot() else { return }
            currentBox.replace(next)
            yield(next)
        }

        @Sendable func commitLoaded(_ loaded: [String: SessionActivity], reconcile: Bool) {
            let now = clock.nowMs()
            optimistic.removeExpired(directory: directory, now: now)
            let previous = serverBox.snapshot()
            let touchedSessions = touched.takeAll()
            var server: [String: SessionActivityEntry] = [:]
            for (sessionID, activity) in loaded where activity != .idle {
                optimistic.remove(directory: directory, sessionID: sessionID)
                server[sessionID] = SessionActivityEntry(
                    activity: activity,
                    source: .server,
                    observedAtMs: now
                )
            }
            for sessionID in touchedSessions {
                if let existing = previous[sessionID] {
                    server[sessionID] = existing
                } else {
                    server.removeValue(forKey: sessionID)
                }
            }
            if reconcile {
                for (sessionID, entry) in previous {
                    guard entry.source == .server, entry.activity.isBusy else { continue }
                    guard !touchedSessions.contains(sessionID) else { continue }
                    let restActivity = loaded[sessionID]
                    let missingOrIdle = restActivity == nil || restActivity == .idle
                    guard missingOrIdle else { continue }
                    if idleUnconfirmed.contains(sessionID) {
                        server.removeValue(forKey: sessionID)
                        idleUnconfirmed.remove(sessionID)
                        DiagnosticLog.info(
                            .session,
                            "statusLiveStore drop unconfirmed session=\(sessionID) directory=\(directory)"
                        )
                    } else {
                        server[sessionID] = entry
                        idleUnconfirmed.insert(sessionID)
                        DiagnosticLog.info(
                            .session,
                            "statusLiveStore keep unconfirmed session=\(sessionID) directory=\(directory)"
                        )
                    }
                }
                for (sessionID, activity) in loaded where activity.isBusy {
                    idleUnconfirmed.remove(sessionID)
                }
            } else {
                idleUnconfirmed.removeAll()
            }
            serverBox.replace(server, seeded: true, fromCache: false)
            persistServer()
            publish()
            if !idleUnconfirmed.isEmpty {
                idleConfirmTask.replace {
                    await clock.sleep(milliseconds: SessionActivityPolicy.idleConfirmMs)
                    guard !Task.isCancelled else { return }
                    guard !idleUnconfirmed.isEmpty else { return }
                    guard canCatchUp(occupancy: worldSignals.occupancy, snapshot: connection.snapshot) else {
                        return
                    }
                    reconcileFlag.requestReconcile()
                    syncCoordinator.invalidate(key)
                    await syncCoordinator.ensureFresh(key)
                }
            }
        }

        @Sendable func loadAndCommit() async throws {
            let generation = syncCoordinator.profileGeneration
            let reconcile = reconcileFlag.takeReconcile()
            let outcome = await gate.refresh(
                load: { () async -> [String: SessionActivity]? in
                    touched.clear()
                    return await Self.loadActivities(
                        directory: directory,
                        sessionRepository: sessionRepository
                    )
                },
                commit: { loaded in
                    guard syncCoordinator.profileGeneration == generation else { return }
                    DiagnosticLog.info(.session, "statusLiveStore refresh directory=\(directory)")
                    commitLoaded(loaded, reconcile: reconcile)
                }
            )
            if outcome == .discarded {
                DiagnosticLog.info(
                    .session,
                    "statusLiveStore discard directory=\(directory)"
                )
            }
            if outcome == .failed {
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: { try await loadAndCommit() })
        syncCoordinator.visible(key, true)
        defer {
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
            expiryTasks.cancelAll()
            idleConfirmTask.cancel()
        }

        let mutationTask = Task {
            for await mutation in mutationBus.events() {
                guard mutation.directory == directory else { continue }
                switch mutation {
                case let .setOptimistic(_, sessionID, ttlMs, observedAtMs):
                    publish()
                    expiryTasks.schedule(sessionID: sessionID) {
                        await clock.sleep(milliseconds: Int(ttlMs))
                        guard !Task.isCancelled else { return }
                        let current = optimistic.snapshot(directory: directory)[sessionID]
                        guard current?.observedAtMs == observedAtMs else { return }
                        optimistic.remove(directory: directory, sessionID: sessionID)
                        publish()
                    }
                }
            }
        }
        defer { mutationTask.cancel() }

        if let cached = await sessionCache.seedActivity(directory: directory), !cached.isEmpty {
            var server: [String: SessionActivityEntry] = [:]
            for entry in cached where entry.activity != .idle {
                server[entry.sessionId] = SessionActivityEntry(
                    activity: entry.activity,
                    source: .cached,
                    observedAtMs: entry.observedAtMs
                )
            }
            if !server.isEmpty {
                serverBox.replace(server, seeded: false, fromCache: true)
                ResumeTiming.shared.markCachePainted()
            }
        }
        publish()

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                while !Task.isCancelled {
                    await clock.sleep(milliseconds: SessionActivityPolicy.runningReconcileMs)
                    guard !Task.isCancelled else { return }
                    let snapshot = currentBox.snapshot()
                    let hasBusy = snapshot.entries.values.contains { $0.activity.isBusy }
                    guard hasBusy else { continue }
                    guard canCatchUp(occupancy: worldSignals.occupancy, snapshot: connection.snapshot) else {
                        continue
                    }
                    reconcileFlag.requestReconcile()
                    syncCoordinator.invalidate(key)
                    await syncCoordinator.ensureFresh(key)
                }
            }
            group.addTask {
                for await event in eventStream.events() {
                    if applyActivityEvent(
                        directory: directory,
                        event: event,
                        eventAdapter: eventAdapter,
                        serverBox: serverBox,
                        optimistic: optimistic,
                        current: currentBox.snapshot(),
                        now: clock.nowMs(),
                        idleUnconfirmed: idleUnconfirmed,
                        touched: touched
                    ) {
                        persistServer()
                        publish()
                    }
                }
            }
            await syncCoordinator.ensureFresh(key)
            await group.next()
            group.cancelAll()
        }
    }

    private nonisolated static func loadActivities(
        directory: String,
        sessionRepository: any SessionRepository
    ) async -> [String: SessionActivity]? {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: directory) else {
            return nil
        }
        var activities: [String: SessionActivity] = [:]
        for status in statuses {
            let activity = SessionActivity(raw: status.raw)
            if activity != .idle {
                activities[status.sessionID] = activity
            }
        }
        return activities
    }

    private nonisolated static func overlay(
        server: [String: SessionActivityEntry],
        optimistic: [String: OptimisticActivityRecord],
        seeded: Bool,
        fromCache: Bool
    ) -> SessionActivitySnapshot {
        var entries = server
        for (sessionID, record) in optimistic {
            entries[sessionID] = SessionActivityEntry(
                activity: record.activity,
                source: .optimistic,
                observedAtMs: record.observedAtMs
            )
        }
        return SessionActivitySnapshot(entries: entries, seeded: seeded, fromCache: fromCache)
    }

    private nonisolated static func applyActivityEvent(
        directory: String,
        event: OpencodeEvent,
        eventAdapter: EventDomainAdapter,
        serverBox: ActivityServerBox,
        optimistic: OptimisticActivityTable,
        current: SessionActivitySnapshot,
        now: Int64,
        idleUnconfirmed: IdleUnconfirmedBox,
        touched: TouchedSessionsBox
    ) -> Bool {
        switch event.type {
        case "session.status":
            guard let parsed = eventAdapter.sessionActivity(from: event) else { return false }
            touched.insert(parsed.sessionID)
            idleUnconfirmed.remove(parsed.sessionID)
            optimistic.remove(directory: directory, sessionID: parsed.sessionID)
            var server = serverBox.snapshot()
            if parsed.activity == .idle {
                guard server[parsed.sessionID] != nil else { return true }
                server.removeValue(forKey: parsed.sessionID)
            } else {
                server[parsed.sessionID] = SessionActivityEntry(
                    activity: parsed.activity,
                    source: .server,
                    observedAtMs: now
                )
            }
            serverBox.replace(server, seeded: serverBox.seeded, fromCache: serverBox.fromCache)
            return true
        case "session.idle":
            guard let sessionID = event.sessionID else { return false }
            touched.insert(sessionID)
            idleUnconfirmed.remove(sessionID)
            optimistic.remove(directory: directory, sessionID: sessionID)
            var server = serverBox.snapshot()
            guard server[sessionID] != nil else { return true }
            server.removeValue(forKey: sessionID)
            serverBox.replace(server, seeded: serverBox.seeded, fromCache: serverBox.fromCache)
            return true
        case "message.updated":
            guard let sessionID = event.sessionID ?? event.rawProperties?["info"]?.dictionaryValue?["sessionID"]?.stringValue
            else { return false }
            guard eventAdapter.isCompletedAssistantMessage(event) else { return false }
            touched.insert(sessionID)
            let visible = current.entries[sessionID]
            guard visible?.source == .optimistic, visible?.activity == .running else { return false }
            optimistic.remove(directory: directory, sessionID: sessionID)
            var server = serverBox.snapshot()
            if server[sessionID] != nil {
                server.removeValue(forKey: sessionID)
                serverBox.replace(server, seeded: serverBox.seeded, fromCache: serverBox.fromCache)
            }
            return true
        default:
            return false
        }
    }
}

private enum ActivityMutation: Sendable {
    case setOptimistic(directory: String, sessionID: String, ttlMs: Int64, observedAtMs: Int64)

    var directory: String {
        switch self {
        case let .setOptimistic(directory, _, _, _):
            directory
        }
    }
}

private nonisolated final class ActivityMutationBus: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<ActivityMutation>.Continuation] = [:]
    private var buffer: [ActivityMutation] = []

    func events() -> AsyncStream<ActivityMutation> {
        let id = UUID()
        return AsyncStream { continuation in
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
            lock.lock()
            let buffered = buffer
            buffer.removeAll()
            continuations[id] = continuation
            for mutation in buffered {
                continuation.yield(mutation)
            }
            lock.unlock()
        }
    }

    func yield(_ mutation: ActivityMutation) {
        lock.lock()
        let subscribers = Array(continuations.values)
        if subscribers.isEmpty {
            if buffer.count >= 32 {
                buffer.removeFirst()
            }
            buffer.append(mutation)
            lock.unlock()
            return
        }
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(mutation)
        }
    }
}

private struct OptimisticActivityRecord: Sendable {
    var activity: SessionActivity
    var observedAtMs: Int64
    var expiresAtMs: Int64
}

private nonisolated final class OptimisticActivityTable: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: [String: OptimisticActivityRecord]] = [:]

    func set(
        directory: String,
        sessionID: String,
        activity: SessionActivity,
        observedAtMs: Int64,
        expiresAtMs: Int64
    ) {
        lock.lock()
        var bucket = values[directory] ?? [:]
        bucket[sessionID] = OptimisticActivityRecord(
            activity: activity,
            observedAtMs: observedAtMs,
            expiresAtMs: expiresAtMs
        )
        values[directory] = bucket
        lock.unlock()
    }

    func remove(directory: String, sessionID: String) {
        lock.lock()
        if var bucket = values[directory] {
            bucket.removeValue(forKey: sessionID)
            values[directory] = bucket
        }
        lock.unlock()
    }

    func snapshot(directory: String) -> [String: OptimisticActivityRecord] {
        lock.lock()
        defer { lock.unlock() }
        return values[directory] ?? [:]
    }

    func removeExpired(directory: String, now: Int64) {
        lock.lock()
        guard var bucket = values[directory] else {
            lock.unlock()
            return
        }
        bucket = bucket.filter { $0.value.expiresAtMs > now }
        values[directory] = bucket
        lock.unlock()
    }

    func removeAll() {
        lock.lock()
        values.removeAll()
        lock.unlock()
    }
}

private nonisolated final class ActivityServerBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [String: SessionActivityEntry] = [:]
    private var _seeded = false
    private var _fromCache = false

    var seeded: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _seeded
    }

    var fromCache: Bool {
        lock.lock()
        defer { lock.unlock() }
        return _fromCache
    }

    func snapshot() -> [String: SessionActivityEntry] {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func replace(_ next: [String: SessionActivityEntry], seeded: Bool, fromCache: Bool) {
        lock.lock()
        value = next
        _seeded = seeded
        _fromCache = fromCache
        lock.unlock()
    }
}

private nonisolated final class ActivitySnapshotBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value = SessionActivitySnapshot.empty

    func snapshot() -> SessionActivitySnapshot {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func replace(_ next: SessionActivitySnapshot) {
        lock.lock()
        value = next
        lock.unlock()
    }
}

private nonisolated final class IdleConfirmTaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var task: Task<Void, Never>?

    func replace(_ body: @escaping @Sendable () async -> Void) {
        lock.lock()
        task?.cancel()
        let next = Task { await body() }
        task = next
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        let running = task
        task = nil
        lock.unlock()
        running?.cancel()
    }
}

private nonisolated final class ReconcileFlagBox: @unchecked Sendable {
    private let lock = NSLock()
    private var pending = false

    func requestReconcile() {
        lock.lock()
        pending = true
        lock.unlock()
    }

    func takeReconcile() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let value = pending
        pending = false
        return value
    }
}

private nonisolated final class IdleUnconfirmedBox: @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: Set<String> = []

    func insert(_ sessionID: String) {
        lock.lock()
        sessions.insert(sessionID)
        lock.unlock()
    }

    func remove(_ sessionID: String) {
        lock.lock()
        sessions.remove(sessionID)
        lock.unlock()
    }

    func removeAll() {
        lock.lock()
        sessions.removeAll()
        lock.unlock()
    }

    func contains(_ sessionID: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return sessions.contains(sessionID)
    }

    var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return sessions.isEmpty
    }
}

private nonisolated final class TouchedSessionsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: Set<String> = []

    func insert(_ sessionID: String) {
        lock.lock()
        sessions.insert(sessionID)
        lock.unlock()
    }

    func clear() {
        lock.lock()
        sessions.removeAll()
        lock.unlock()
    }

    func takeAll() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        let snapshot = sessions
        sessions.removeAll()
        return snapshot
    }
}

private nonisolated final class ExpiryTaskBag: @unchecked Sendable {
    private let lock = NSLock()
    private var tasks: [String: Task<Void, Never>] = [:]

    func schedule(sessionID: String, body: @escaping @Sendable () async -> Void) {
        lock.lock()
        tasks[sessionID]?.cancel()
        let task = Task { await body() }
        tasks[sessionID] = task
        lock.unlock()
    }

    func cancelAll() {
        lock.lock()
        let running = Array(tasks.values)
        tasks.removeAll()
        lock.unlock()
        running.forEach { $0.cancel() }
    }
}
