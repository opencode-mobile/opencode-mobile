import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let invalidationAckTimeoutMs: Int
    private let capabilities: @Sendable () -> ServerCapabilities
    private let activeDirectories = ActiveDirectorySet()
    private let viewedWatermarks = SessionViewedWatermarks()
    private let loops = SessionListLoopRegistry()
    private let windows = SessionWindowRegistry()
    let ancestry: SessionAncestry
    private var cache: [String: SharedLiveObservation<SessionListSnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil,
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs,
        capabilities: @escaping @Sendable () -> ServerCapabilities = { .forProtocol(.v1) }
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
        self.capabilities = capabilities
        self.ancestry = SessionAncestry { sessionID, directory in
            try await sessionRepository.getSession(id: sessionID, directory: directory)
        }
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
        viewedWatermarks.removeAll()
        windows.removeAll()
        ancestry.clear()
        stale.forEach { $0.finish() }
    }

    func loadMore(directory: String) async {
        guard let reload = windows.beginLoadMore(directory: directory) else { return }
        defer { windows.endLoadMore(directory: directory) }
        // Off the main actor, like the loop's own loads: the reply decodes the whole window.
        _ = try? await Task.detached { try await reload() }.value
    }

    func observeKnownSession(sessionId: String) -> AsyncStream<Session> {
        loops.observe(sessionId: sessionId)
    }

    func applySessionViewed(sessionId: String, idleAtEpochMs: Int64) {
        viewedWatermarks.raise(sessionId: sessionId, idleAtEpochMs: idleAtEpochMs)
        loops.applyViewed(sessionId: sessionId, watermarks: viewedWatermarks)
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
        let capabilities = capabilities
        let viewedWatermarks = viewedWatermarks
        let loops = loops
        let windows = windows
        let ancestry = ancestry
        let observation = SharedLiveObservation<SessionListSnapshot> { yield in
            Task.detached {
                await Self.runSessionLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    sessionCache: sessionCache,
                    syncCoordinator: syncCoordinator,
                    activeDirectories: activeDirectories,
                    capabilities: capabilities,
                    viewedWatermarks: viewedWatermarks,
                    loops: loops,
                    windows: windows,
                    ancestry: ancestry,
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
        capabilities: @escaping @Sendable () -> ServerCapabilities,
        viewedWatermarks: SessionViewedWatermarks,
        loops: SessionListLoopRegistry,
        windows: SessionWindowRegistry,
        ancestry: SessionAncestry,
        yield: @escaping @Sendable (SessionListSnapshot) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let key = ProjectionKey.sessionList(directory: directory)
        let gate = LiveStoreRefreshGate()
        let loop = SessionListLoopState(yield: yield, onPublish: { loops.deliver($0) })
        let loopID = loops.insert(loop)
        defer { loops.remove(loopID) }
        let eventAdapter = EventDomainAdapter()

        @Sendable func loadAndCommit() async throws {
            let generation = syncCoordinator.profileGeneration
            let outcome = await gate.refresh(
                load: { () async -> LoadedSessionWindow? in
                    // The current window size, in one request, for every refetch trigger.
                    await Self.loadWindow(
                        directory: directory,
                        limit: windows.limit(directory: directory),
                        sessionRepository: sessionRepository
                    )
                },
                commit: { loaded in
                    guard syncCoordinator.profileGeneration == generation else { return }
                    ancestry.record(loaded.window.sessions + loaded.window.children)
                    windows.committed(directory: directory, limit: loaded.limit, hasMore: loaded.window.hasMore)
                    // REST can race a `session.viewed`; the viewed watermark only moves forward.
                    let sessions = viewedWatermarks.apply(to: loaded.window.sessions)
                    await sessionCache.rememberSessions(worktree: directory, sessions: sessions)
                    // A view can land during the cache write; re-apply under the loop lock so the
                    // publish cannot overwrite it with the pre-await list.
                    loop.publish(
                        SessionListSnapshot(sessions: sessions, isFromCache: false, hasMore: loaded.window.hasMore),
                        applying: viewedWatermarks
                    )
                    DiagnosticLog.info(
                        .session,
                        "sessionLiveStore refresh directory=\(directory) count=\(sessions.count) limit=\(loaded.limit) more=\(loaded.window.hasMore)"
                    )
                }
            )
            if outcome == .failed {
                // Observers learn the server load failed; the coordinator keeps its retry backoff.
                // A cancelled refresh (superseded or torn down) is not a server failure.
                if !Task.isCancelled, syncCoordinator.profileGeneration == generation {
                    loop.publishLoadFailed()
                }
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: loadAndCommit)
        syncCoordinator.visible(key, true)
        let windowID = windows.attach(directory: directory, reload: loadAndCommit)
        defer {
            windows.detach(directory: directory, id: windowID)
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
        }

        if let seeded = await sessionCache.seedSessions(worktree: directory) {
            ResumeTiming.shared.markCachePainted()
            ancestry.record(seeded)
            windows.seededFromCache(directory: directory, count: seeded.count)
            loop.publish(SessionListSnapshot(sessions: seeded, isFromCache: true), applying: viewedWatermarks)
        }
        await syncCoordinator.ensureFresh(key)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                var refreshTask: Task<Void, Never>?
                defer { refreshTask?.cancel() }
                for await event in eventStream.events() {
                    if let viewed = eventAdapter.sessionViewed(event) {
                        // Patch in place: another device (or this one) viewed the run. No refetch.
                        viewedWatermarks.raise(sessionId: viewed.sessionID, idleAtEpochMs: viewed.idleAtEpochMs)
                        if let patched = loop.applyViewed(sessionId: viewed.sessionID, watermarks: viewedWatermarks),
                           !patched.isFromCache
                        {
                            await sessionCache.rememberSessions(worktree: directory, sessions: patched.sessions)
                        }
                        continue
                    }
                    // v2 run end moves `time.idle` / `outcome` without a `session.updated`, so
                    // refetch for the unread state. v1 has no server unread state: unchanged.
                    let runEndNeedsRefresh = event.isSessionRunEnd && capabilities().supportsSessionViewed
                    guard event.triggersHomeRefresh || runEndNeedsRefresh else { continue }
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

    private nonisolated static func loadWindow(
        directory: String,
        limit: Int,
        sessionRepository: any SessionRepository
    ) async -> LoadedSessionWindow? {
        guard let window = try? await sessionRepository.listRootSessionsWindow(directory: directory, limit: limit) else {
            return nil
        }
        return LoadedSessionWindow(window: window, limit: limit)
    }
}

/// One server window and the size it was asked for.
nonisolated struct LoadedSessionWindow: Sendable {
    let window: SessionWindow
    let limit: Int
}

/// Window size and load more state of each directory a live session store lists. Sizes outlive
/// an observation (Home leaving and coming back keeps its window) until the store clears.
nonisolated final class SessionWindowRegistry: @unchecked Sendable {
    typealias Reload = @Sendable () async throws -> Void

    private struct Entry {
        var limit = HomeSessionPaging.pageSize
        /// Size of the last committed server load; nil before one.
        var loadedLimit: Int?
        var hasMore = false
        var loadingMore = false
        var reload: Reload?
        var reloadID: UUID?
    }

    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    func limit(directory: String) -> Int {
        lock.withLock { entries[directory]?.limit ?? HomeSessionPaging.pageSize }
    }

    /// The Session Cache painted `count` rows before any server load. The first load asks for
    /// that many (whole pages, at most ``HomeSessionPaging/maxSeededPages``) so a cold start
    /// does not shrink a window the user had grown with load more.
    func seededFromCache(directory: String, count: Int) {
        guard count > 0 else { return }
        let pageSize = HomeSessionPaging.pageSize
        let pages = min((count + pageSize - 1) / pageSize, HomeSessionPaging.maxSeededPages)
        lock.withLock {
            var entry = entries[directory] ?? Entry()
            guard entry.loadedLimit == nil else { return }
            entry.limit = max(entry.limit, pages * pageSize)
            entries[directory] = entry
        }
    }

    func committed(directory: String, limit: Int, hasMore: Bool) {
        lock.withLock {
            var entry = entries[directory] ?? Entry()
            entry.loadedLimit = limit
            entry.hasMore = hasMore
            entries[directory] = entry
        }
    }

    func attach(directory: String, reload: @escaping Reload) -> UUID {
        let id = UUID()
        lock.withLock {
            var entry = entries[directory] ?? Entry()
            entry.reload = reload
            entry.reloadID = id
            entries[directory] = entry
        }
        return id
    }

    func detach(directory: String, id: UUID) {
        lock.withLock {
            guard entries[directory]?.reloadID == id else { return }
            entries[directory]?.reload = nil
            entries[directory]?.reloadID = nil
        }
    }

    /// Grows the window one page past the last server load and returns its loader. Nil while a
    /// load more runs, before the first server load, and when that load had nothing more.
    func beginLoadMore(directory: String) -> Reload? {
        lock.withLock {
            guard var entry = entries[directory], let reload = entry.reload, let loaded = entry.loadedLimit,
                  entry.hasMore, !entry.loadingMore
            else { return nil }
            entry.loadingMore = true
            entry.limit = max(entry.limit, loaded + HomeSessionPaging.pageSize)
            entries[directory] = entry
            return reload
        }
    }

    func endLoadMore(directory: String) {
        lock.withLock { entries[directory]?.loadingMore = false }
    }

    func removeAll() {
        lock.withLock { entries.removeAll() }
    }
}

/// Monotone `time.viewed` watermarks learned from `session.viewed` or this device's own
/// acknowledgements. A REST list that raced the update cannot move a session back to unread.
nonisolated final class SessionViewedWatermarks: @unchecked Sendable {
    private let lock = NSLock()
    private var viewedBySession: [String: Int64] = [:]

    func raise(sessionId: String, idleAtEpochMs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        viewedBySession[sessionId] = max(viewedBySession[sessionId] ?? idleAtEpochMs, idleAtEpochMs)
    }

    func removeAll() {
        lock.lock()
        defer { lock.unlock() }
        viewedBySession.removeAll()
    }

    func apply(to sessions: [Session]) -> [Session] {
        lock.lock()
        let watermarks = viewedBySession
        lock.unlock()
        guard !watermarks.isEmpty else { return sessions }
        return sessions.map { session in
            guard let viewed = watermarks[session.id] else { return session }
            return session.withViewed(idleAtEpochMs: viewed)
        }
    }

    func apply(to session: Session) -> Session {
        apply(to: [session])[0]
    }
}

/// Latest published list of one running session-list loop.
nonisolated final class SessionListLoopState: @unchecked Sendable {
    private let lock = NSLock()
    private let yield: @Sendable (SessionListSnapshot) -> Void
    private let onPublish: @Sendable ([Session]) -> Void
    private var latest: SessionListSnapshot?

    init(
        yield: @escaping @Sendable (SessionListSnapshot) -> Void,
        onPublish: @escaping @Sendable ([Session]) -> Void = { _ in }
    ) {
        self.yield = yield
        self.onPublish = onPublish
    }

    /// Publishes `snapshot`. With `watermarks`, they are applied under the same lock as
    /// ``applyViewed(sessionId:watermarks:)``, so a view raised before this call is never lost.
    func publish(_ snapshot: SessionListSnapshot, applying watermarks: SessionViewedWatermarks? = nil) {
        lock.lock()
        defer { lock.unlock() }
        let published = watermarks.map {
            SessionListSnapshot(
                sessions: $0.apply(to: snapshot.sessions),
                isFromCache: snapshot.isFromCache,
                loadFailed: snapshot.loadFailed,
                hasMore: snapshot.hasMore
            )
        } ?? snapshot
        latest = published
        yield(published)
        onPublish(published.sessions)
    }

    /// Reports a failed server load: republishes the sessions already known (none before the
    /// first load) as cached, with `loadFailed` set.
    func publishLoadFailed() {
        lock.lock()
        defer { lock.unlock() }
        let failed = SessionListSnapshot(
            sessions: latest?.sessions ?? [],
            isFromCache: true,
            loadFailed: true,
            hasMore: latest?.hasMore ?? false
        )
        latest = failed
        yield(failed)
    }

    func session(id: String) -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return latest?.sessions.first { $0.id == id }
    }

    /// Republishes the list when it holds `sessionId` and the watermark changes it.
    @discardableResult
    func applyViewed(sessionId: String, watermarks: SessionViewedWatermarks) -> SessionListSnapshot? {
        lock.lock()
        defer { lock.unlock() }
        guard let current = latest,
              let index = current.sessions.firstIndex(where: { $0.id == sessionId })
        else { return nil }
        let patchedSession = watermarks.apply(to: current.sessions[index])
        guard patchedSession != current.sessions[index] else { return nil }
        var sessions = current.sessions
        sessions[index] = patchedSession
        // The failure flag belongs to the publish that reported it, not to later patches.
        let patched = SessionListSnapshot(sessions: sessions, isFromCache: current.isFromCache, hasMore: current.hasMore)
        latest = patched
        yield(patched)
        onPublish(sessions)
        return patched
    }
}

nonisolated final class SessionListLoopRegistry: @unchecked Sendable {
    private struct Watcher {
        let sessionId: String
        let continuation: AsyncStream<Session>.Continuation
        var last: Session?
    }

    private let lock = NSLock()
    private var loops: [UUID: SessionListLoopState] = [:]
    private var watchers: [UUID: Watcher] = [:]

    func observe(sessionId: String) -> AsyncStream<Session> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            watchers[id] = Watcher(sessionId: sessionId, continuation: continuation, last: nil)
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.watchers.removeValue(forKey: id)
                self.lock.unlock()
            }
            if let current = session(id: sessionId) {
                deliver([current])
            }
        }
    }

    /// Yields each watched session whose known state changed.
    func deliver(_ sessions: [Session]) {
        lock.lock()
        guard !watchers.isEmpty else {
            lock.unlock()
            return
        }
        var pending: [(AsyncStream<Session>.Continuation, Session)] = []
        for (id, watcher) in watchers {
            guard let session = sessions.first(where: { $0.id == watcher.sessionId }),
                  session != watcher.last
            else { continue }
            watchers[id]?.last = session
            pending.append((watcher.continuation, session))
        }
        lock.unlock()
        for (continuation, session) in pending {
            continuation.yield(session)
        }
    }

    func insert(_ loop: SessionListLoopState) -> UUID {
        let id = UUID()
        lock.lock()
        loops[id] = loop
        lock.unlock()
        return id
    }

    func remove(_ id: UUID) {
        lock.lock()
        loops.removeValue(forKey: id)
        lock.unlock()
    }

    func session(id: String) -> Session? {
        snapshot().lazy.compactMap { $0.session(id: id) }.first
    }

    func applyViewed(sessionId: String, watermarks: SessionViewedWatermarks) {
        for loop in snapshot() {
            loop.applyViewed(sessionId: sessionId, watermarks: watermarks)
        }
    }

    private func snapshot() -> [SessionListLoopState] {
        lock.lock()
        defer { lock.unlock() }
        return Array(loops.values)
    }
}
