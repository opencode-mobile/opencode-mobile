import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private let worldSignals: any WorldSignals
    private let invalidationBus: StoreInvalidationBus
    private let activeDirectories = ActiveDirectorySet()
    private var cache: [String: SharedLiveObservation<SessionListSnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.sessionCache = sessionCache
        self.worldSignals = worldSignals
        invalidationBus = StoreInvalidationBus(ackTimeoutMs: invalidationAckTimeoutMs)
    }

    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) async {
        let keys: [String]
        if let directory {
            keys = activeDirectories.contains(directory) ? [directory] : []
        } else {
            keys = activeDirectories.snapshot()
        }
        let bus = invalidationBus
        await withTaskGroup(of: Void.self) { group in
            for key in keys {
                group.addTask {
                    await bus.invalidateAndWait(
                        key: key,
                        reason: StoreRefreshReason.pullToRefresh
                    )
                }
            }
        }
    }

    func clearCache() {
        let stale = Array(cache.values)
        cache.removeAll()
        stale.forEach { $0.finish() }
    }

    private func observation(for directory: String) -> SharedLiveObservation<SessionListSnapshot> {
        if let existing = cache[directory], !existing.isTerminated {
            return existing
        }
        let sessionRepository = sessionRepository
        let eventStream = eventStream
        let sessionCache = sessionCache
        let worldSignals = worldSignals
        let invalidationBus = invalidationBus
        let activeDirectories = activeDirectories
        let observation = SharedLiveObservation<SessionListSnapshot> { yield in
            Task.detached {
                await Self.runSessionLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    sessionCache: sessionCache,
                    worldSignals: worldSignals,
                    invalidationBus: invalidationBus,
                    activeDirectories: activeDirectories,
                    yield: yield
                )
            }
        }
        cache[directory] = observation
        return observation
    }

    private nonisolated static func runSessionLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        worldSignals: any WorldSignals,
        invalidationBus: StoreInvalidationBus,
        activeDirectories: ActiveDirectorySet,
        yield: @escaping @Sendable (SessionListSnapshot) -> Void
    ) async {
        let invalidations = invalidationBus.events()
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let liveConnection = eventStream.asLiveConnection()
        let authority = GapAuthority()
        let gate = LiveStoreRefreshGate()

        @Sendable func refreshFromNetwork(reason: String, force: Bool) async -> Bool {
            let snapshot = liveConnection.snapshot
            let generation: UInt64?
            if force {
                generation = nil
            } else {
                guard let started = authority.tryBegin(snapshot) else {
                    DiagnosticLog.info(
                        .session,
                        "sessionLiveStore skip directory=\(directory) reason=\(reason)"
                    )
                    return false
                }
                generation = started
            }
            let outcome = await gate.refresh(
                load: { () async -> [Session]? in
                    await Self.loadSessions(
                        directory: directory,
                        sessionRepository: sessionRepository
                    )
                },
                commit: { sessions in
                    await sessionCache.rememberSessions(worktree: directory, sessions: sessions)
                    yield(SessionListSnapshot(sessions: sessions, isFromCache: false))
                    DiagnosticLog.info(
                        .session,
                        "sessionLiveStore refresh directory=\(directory) reason=\(reason) "
                            + "count=\(sessions.count)"
                    )
                }
            )
            if let generation {
                switch outcome {
                case .committed:
                    authority.acknowledge(generation)
                case .failed, .discarded:
                    authority.fail(generation)
                }
            }
            return outcome == .committed
        }

        if let seeded = await sessionCache.seedSessions(worktree: directory) {
            yield(SessionListSnapshot(sessions: seeded, isFromCache: true))
        }

        _ = await refreshFromNetwork(reason: StoreRefreshReason.initialSubscribe, force: true)
        authority.acknowledgeCurrent(liveConnection.snapshot)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        authority.invalidate(liveConnection.snapshot)
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    _ = await refreshFromNetwork(reason: reason, force: false)
                }
            }
            group.addTask {
                for await _ in liveConnection.snapshotUpdates() {
                    _ = await refreshFromNetwork(
                        reason: StoreRefreshReason.streamReconnected,
                        force: false
                    )
                }
            }
            group.addTask {
                for await invalidation in invalidations {
                    if let key = invalidation.key, key != directory { continue }
                    let ack = invalidation.ack
                    defer { ack?() }
                    authority.invalidate(liveConnection.snapshot)
                    _ = await refreshFromNetwork(reason: invalidation.reason, force: false)
                }
            }
            group.addTask {
                var refreshTask: Task<Void, Never>?
                defer { refreshTask?.cancel() }
                for await event in eventStream.events() {
                    guard event.type == "stream.reconnected"
                        || event.type == "server.connected"
                        || event.triggersHomeRefresh
                    else { continue }
                    refreshTask?.cancel()
                    let reason = event.type
                    let mutation = reason != "stream.reconnected" && reason != "server.connected"
                    refreshTask = Task {
                        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
                        guard !Task.isCancelled else { return }
                        _ = await refreshFromNetwork(reason: reason, force: mutation)
                    }
                }
            }
            await group.next()
            group.cancelAll()
        }
    }

    private nonisolated static func loadSessions(
        directory: String,
        sessionRepository: any SessionRepository
    ) async -> [Session]? {
        try? await sessionRepository.listSessions(directory: directory)
    }
}
