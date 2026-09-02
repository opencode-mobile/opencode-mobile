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
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let sessionCache = sessionCache
            let worldSignals = worldSignals
            let invalidationBus = invalidationBus
            let activeDirectories = activeDirectories
            cache[directory] = SharedLiveObservation { yield in
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
        }
        return cache[directory]!.stream()
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
        // Finish before dropping: a dropped-but-unfinished observation leaves every
        // subscriber suspended forever instead of letting it re-subscribe.
        cache.values.forEach { $0.finish() }
        cache.removeAll()
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
        // Subscribe to the invalidation bus BEFORE publishing this directory as
        // invalidatable. `invalidateAndWait` drops an event no collector is listening
        // for, and `invalidate` targets whatever is in `activeDirectories`, so the
        // window between those two facts is a window where a pull-to-refresh is
        // silently swallowed: no catch-up GET, and a spinner that vanishes instantly.
        // The window is real — the collectors only start after the initial load — and
        // grew with the refresh gate's extra hops. `AsyncStream` buffers, so an
        // invalidation that lands while the first load runs is still delivered.
        let invalidations = invalidationBus.events()
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let stale = StaleBit()
        // Single-flight gate for this observation: the world-signal, invalidation
        // and event collectors all call refreshFromNetwork, and two overlapping
        // `listSessions` responses can land in the wrong order. Nothing else
        // mutates this store's state, so the revision half never discards here —
        // serialising the GETs is what keeps the newest response last.
        let gate = LiveStoreRefreshGate()

        @Sendable func refreshFromNetwork(reason: String, force: Bool) async -> Bool {
            if !force, !stale.isSet {
                DiagnosticLog.info(
                    .session,
                    "sessionLiveStore skip directory=\(directory) reason=\(reason)"
                )
                return false
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
                    stale.clear()
                    DiagnosticLog.info(
                        .session,
                        "sessionLiveStore refresh directory=\(directory) reason=\(reason) "
                            + "count=\(sessions.count)"
                    )
                }
            )
            return outcome == .committed
        }

        if let seeded = await sessionCache.seedSessions(worktree: directory) {
            yield(SessionListSnapshot(sessions: seeded, isFromCache: true))
        }

        _ = await refreshFromNetwork(reason: StoreRefreshReason.initialSubscribe, force: true)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        stale.mark()
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    _ = await refreshFromNetwork(reason: reason, force: false)
                }
            }
            group.addTask {
                for await invalidation in invalidations {
                    if let key = invalidation.key, key != directory { continue }
                    // Ack on every exit from the iteration, including a torn-down or
                    // failed refresh: the ack releases the caller's spinner, it is not
                    // a "data is fresh" signal.
                    let ack = invalidation.ack
                    defer { ack?() }
                    stale.mark()
                    _ = await refreshFromNetwork(reason: invalidation.reason, force: true)
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
                    refreshTask = Task {
                        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
                        guard !Task.isCancelled else { return }
                        _ = await refreshFromNetwork(reason: reason, force: true)
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
