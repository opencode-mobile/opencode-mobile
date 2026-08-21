import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private let worldSignals: any WorldSignals
    private let invalidationBus = StoreInvalidationBus()
    private let activeDirectories = ActiveDirectorySet()
    private var cache: [String: SharedLiveObservation<SessionListSnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        worldSignals: any WorldSignals = SilentWorldSignals.shared
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.sessionCache = sessionCache
        self.worldSignals = worldSignals
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
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let stale = StaleBit()

        func refreshFromNetwork(reason: String, force: Bool) async -> Bool {
            if !force, !stale.isSet {
                DiagnosticLog.info(
                    .session,
                    "sessionLiveStore skip directory=\(directory) reason=\(reason)"
                )
                return false
            }
            guard let sessions = await loadSessions(
                directory: directory,
                sessionRepository: sessionRepository
            ) else { return false }
            await sessionCache.rememberSessions(worktree: directory, sessions: sessions)
            yield(SessionListSnapshot(sessions: sessions, isFromCache: false))
            stale.clear()
            DiagnosticLog.info(
                .session,
                "sessionLiveStore refresh directory=\(directory) reason=\(reason) count=\(sessions.count)"
            )
            return true
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
                for await invalidation in invalidationBus.events() {
                    if let key = invalidation.key, key != directory { continue }
                    stale.mark()
                    _ = await refreshFromNetwork(reason: invalidation.reason, force: true)
                    invalidation.ack?()
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
