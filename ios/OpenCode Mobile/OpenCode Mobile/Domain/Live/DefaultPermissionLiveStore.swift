import Foundation

@MainActor
final class DefaultPermissionLiveStore: PermissionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let activeDirectories = ActiveDirectorySet()
    private let sessionIndex = PermissionSessionIndex()
    private var cache: [String: SharedLiveObservation<[PermissionRequest]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
    }

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) {
        let keys: [ProjectionKey]
        if let directory {
            keys = activeDirectories.contains(directory) ? [.permissions(directory: directory)] : []
        } else {
            keys = activeDirectories.snapshot().map { .permissions(directory: $0) }
        }
        let coordinator = syncCoordinator
        for key in keys {
            coordinator.invalidate(key)
            Task {
                await coordinator.ensureFresh(key)
            }
        }
    }

    func noteSessions(directory: String, sessionIDs: Set<String>) {
        sessionIndex.note(directory: directory, sessionIDs: sessionIDs)
    }

    func clearSessionIndex() {
        sessionIndex.clear()
    }

    func clearCache() {
        let stale = Array(cache.values)
        cache.removeAll()
        stale.forEach { $0.finish() }
    }

    private func observation(for directory: String) -> SharedLiveObservation<[PermissionRequest]> {
        if let existing = cache[directory], !existing.isTerminated {
            return existing
        }
        let sessionRepository = sessionRepository
        let eventStream = eventStream
        let syncCoordinator = syncCoordinator
        let activeDirectories = activeDirectories
        let sessionIndex = sessionIndex
        let observation = SharedLiveObservation<[PermissionRequest]> { yield in
            Task.detached {
                await Self.runLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    syncCoordinator: syncCoordinator,
                    activeDirectories: activeDirectories,
                    sessionIndex: sessionIndex,
                    yield: yield
                )
            }
        }
        cache[directory] = observation
        return observation
    }

    private nonisolated static func runLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        syncCoordinator: any SyncCoordinator,
        activeDirectories: ActiveDirectorySet,
        sessionIndex: PermissionSessionIndex,
        yield: @escaping @Sendable ([PermissionRequest]) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let key = ProjectionKey.permissions(directory: directory)
        let gate = LiveStoreRefreshGate()
        let lastEmitted = PermissionEmitCache()

        @Sendable func loadAndCommit() async throws {
            let generation = syncCoordinator.profileGeneration
            let outcome = await gate.refresh(
                load: { () async -> [PermissionRequest]? in
                    try? await sessionRepository.listPendingPermissions(directory: directory)
                },
                commit: { requests in
                    guard syncCoordinator.profileGeneration == generation else { return }
                    sessionIndex.record(
                        directory: directory,
                        sessionIDs: Set(requests.map(\.sessionID))
                    )
                    DiagnosticLog.info(.permission, "permission refresh count=\(requests.count)")
                    guard lastEmitted.shouldEmit(requests) else {
                        DiagnosticLog.info(.permission, "permission emit skip cause=unchanged")
                        return
                    }
                    yield(requests)
                }
            )
            if outcome == .failed {
                DiagnosticLog.warning(.permission, "permission refresh failed")
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: loadAndCommit)
        syncCoordinator.visible(key, true)
        defer {
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
        }
        await syncCoordinator.ensureFresh(key)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await event in eventStream.events() {
                    guard event.type == "permission.asked" || event.type == "permission.replied" else {
                        continue
                    }
                    // Events name a session and no directory. An index limits the refetch to the
                    // project that owns that session. No index keeps the chat-wide refresh.
                    guard sessionIndex.shouldRefresh(directory: directory, sessionID: event.sessionID) else {
                        continue
                    }
                    Task {
                        try? await loadAndCommit()
                    }
                }
            }
            await group.next()
            group.cancelAll()
        }
    }
}

/// Directory → session ids. A missing directory means "refresh on any permission event".
private nonisolated final class PermissionSessionIndex: @unchecked Sendable {
    private let lock = NSLock()
    private var indexed: [String: Set<String>] = [:]
    private var known: [String: Set<String>] = [:]

    func note(directory: String, sessionIDs: Set<String>) {
        lock.lock()
        indexed[directory] = sessionIDs
        lock.unlock()
    }

    func clear() {
        lock.lock()
        indexed.removeAll()
        known.removeAll()
        lock.unlock()
    }

    func record(directory: String, sessionIDs: Set<String>) {
        lock.lock()
        known[directory] = sessionIDs
        lock.unlock()
    }

    func shouldRefresh(directory: String, sessionID: String?) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let sessions = indexed[directory] else { return true }
        guard let sessionID else { return true }
        if sessions.contains(sessionID) { return true }
        return known[directory]?.contains(sessionID) == true
    }
}

private nonisolated final class PermissionEmitCache: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [PermissionRequest]?

    func shouldEmit(_ next: [PermissionRequest]) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if let value, value == next { return false }
        value = next
        return true
    }
}
