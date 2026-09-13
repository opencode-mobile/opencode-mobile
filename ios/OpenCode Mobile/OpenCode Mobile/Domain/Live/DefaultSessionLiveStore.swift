import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let invalidationAckTimeoutMs: Int
    private let activeDirectories = ActiveDirectorySet()
    private var cache: [String: SharedLiveObservation<SessionListSnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil,
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.sessionCache = sessionCache
        self.worldSignals = worldSignals
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
        self.invalidationAckTimeoutMs = invalidationAckTimeoutMs
    }

    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) async {
        let keys: [ProjectionKey]
        if let directory {
            keys = activeDirectories.contains(directory) ? [.sessionList(directory: directory)] : []
        } else {
            keys = activeDirectories.snapshot().map { .sessionList(directory: $0) }
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
        let syncCoordinator = syncCoordinator
        let activeDirectories = activeDirectories
        let observation = SharedLiveObservation<SessionListSnapshot> { yield in
            Task.detached {
                await Self.runSessionLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    sessionCache: sessionCache,
                    syncCoordinator: syncCoordinator,
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
        syncCoordinator: any SyncCoordinator,
        activeDirectories: ActiveDirectorySet,
        yield: @escaping @Sendable (SessionListSnapshot) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let key = ProjectionKey.sessionList(directory: directory)
        let gate = LiveStoreRefreshGate()

        @Sendable func loadAndCommit() async throws {
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
                        "sessionLiveStore refresh directory=\(directory) count=\(sessions.count)"
                    )
                }
            )
            if outcome == .failed {
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: loadAndCommit)
        syncCoordinator.visible(key, true)
        defer {
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
        }

        if let seeded = await sessionCache.seedSessions(worktree: directory) {
            yield(SessionListSnapshot(sessions: seeded, isFromCache: true))
        }
        await syncCoordinator.ensureFresh(key)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                var refreshTask: Task<Void, Never>?
                defer { refreshTask?.cancel() }
                for await event in eventStream.events() {
                    guard event.triggersHomeRefresh else { continue }
                    refreshTask?.cancel()
                    refreshTask = Task {
                        try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
                        guard !Task.isCancelled else { return }
                        try? await loadAndCommit()
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
