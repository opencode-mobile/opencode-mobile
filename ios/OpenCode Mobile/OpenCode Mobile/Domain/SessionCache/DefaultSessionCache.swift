import Foundation

nonisolated final class DefaultSessionCache: SessionCache, @unchecked Sendable {
    private let store: any SessionCacheStore
    private let connectionIdProvider: @Sendable () -> String?
    private let clock: @Sendable () -> Int64
    private let debounceNanoseconds: UInt64
    private let activityDebounceNanoseconds: UInt64
    /// Tool-image thumbnails on disk, kept in step with the cached message timelines.
    private let thumbnails: ToolThumbnailStore?
    private let activityLock = NSLock()
    /// Keyed by connection and directory: two servers can hold the same path.
    private var pendingActivity: [ActivityKey: [SessionActivityCacheEntry]] = [:]
    private var lastActivity: [ActivityKey: [SessionActivityCacheEntry]] = [:]
    private var activityDebounceTasks: [ActivityKey: Task<Void, Never>] = [:]
    private let clears = SessionCacheClearLedger()
    /// Small Home records (project resolutions, worktree layouts), already encoded.
    private let homeRecordLock = NSLock()
    private var pendingHomeRecords: [SessionCacheKey: String] = [:]
    /// The last queued write; each one waits for the one before, so writes land in call order.
    private var homeRecordWrites: Task<Void, Never>?

    init(
        store: any SessionCacheStore,
        connectionIdProvider: @escaping @Sendable () -> String?,
        clock: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1000)
        },
        debounceMilliseconds: UInt64 = 3_000,
        activityDebounceMilliseconds: UInt64 = SessionActivityPolicy.activityRememberDebounceMs,
        thumbnails: ToolThumbnailStore? = nil
    ) {
        self.thumbnails = thumbnails
        self.store = store
        self.connectionIdProvider = connectionIdProvider
        self.clock = clock
        self.debounceNanoseconds = debounceMilliseconds * 1_000_000
        self.activityDebounceNanoseconds = activityDebounceMilliseconds * 1_000_000
    }

    func seedSessions(worktree: String) async -> [Session]? {
        guard let connectionId = connectionIdProvider() else { return nil }
        return await seedSessions(worktree: worktree, connectionID: connectionId)
    }

    func seedSessions(worktree: String, connectionID: String) async -> [Session]? {
        let key = SessionCacheKey(connectionId: connectionID, worktree: worktree, kind: .sessionList)
        return await Self.readSessions(store: store, key: key)
    }

    // Home's cached paint runs on the main actor; the SwiftData read and JSON decode must not.
    @concurrent
    private static func readSessions(store: any SessionCacheStore, key: SessionCacheKey) async -> [Session]? {
        guard let record = await store.load(key: key) else { return nil }
        return SessionCacheCodec.decodeSessions(record.value.payloadJSON)
    }

    @concurrent
    private static func readActivity(
        store: any SessionCacheStore,
        key: SessionCacheKey
    ) async -> [SessionActivityCacheEntry]? {
        guard let record = await store.load(key: key) else { return nil }
        return SessionCacheCodec.decodeActivity(record.value.payloadJSON)
    }

    @concurrent
    private static func readProjectResolution(
        store: any SessionCacheStore,
        key: SessionCacheKey
    ) async -> HomeProjectResolution? {
        guard let record = await store.load(key: key) else { return nil }
        return SessionCacheCodec.decodeProjectResolution(record.value.payloadJSON)
    }

    @concurrent
    private static func readWorktreeLayout(
        store: any SessionCacheStore,
        key: SessionCacheKey
    ) async -> HomeWorktreeLayout? {
        guard let record = await store.load(key: key) else { return nil }
        return SessionCacheCodec.decodeWorktreeLayout(record.value.payloadJSON)
    }

    func rememberSessions(worktree: String, sessions: [Session]) async {
        guard let connectionId = connectionIdProvider() else { return }
        let key = SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .sessionList)
        do {
            let payload = try SessionCacheCodec.encodeSessions(sessions)
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
        } catch {
            DiagnosticLog.warning(.session, "encode/commit sessions failed: \(error.localizedDescription)")
        }
    }

    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]? {
        guard let connectionId = connectionIdProvider() else { return nil }
        let key = SessionCacheKey(connectionId: connectionId, worktree: directory, kind: .sessionActivity)
        guard var entries = await Self.readActivity(store: store, key: key) else { return nil }
        let cutoff = clock() - SessionActivityPolicy.cachedActivityTtlMs
        entries = entries.filter { $0.observedAtMs >= cutoff }
        activityLock.lock()
        lastActivity[ActivityKey(connectionId: connectionId, directory: directory)] = entries
        activityLock.unlock()
        return entries
    }

    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async {
        // The key is fixed now: the debounced write lands under this connection after a switch.
        guard let connectionId = connectionIdProvider() else { return }
        let activityKey = ActivityKey(connectionId: connectionId, directory: directory)
        let normalized = entries.sorted { $0.sessionId < $1.sessionId }
        activityLock.lock()
        if lastActivity[activityKey] == normalized, pendingActivity[activityKey] == nil {
            activityLock.unlock()
            return
        }
        if pendingActivity[activityKey] == normalized {
            activityLock.unlock()
            return
        }
        pendingActivity[activityKey] = normalized
        activityDebounceTasks[activityKey]?.cancel()
        let delay = activityDebounceNanoseconds
        if delay == 0 {
            // Flush inline and only inline: a parallel task could take the
            // pending entries first and still be committing when the caller
            // returns, so a read straight after would miss them.
            activityDebounceTasks[activityKey] = nil
            activityLock.unlock()
            await flushActivity(activityKey)
            return
        }
        activityDebounceTasks[activityKey] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            await self?.flushActivity(activityKey)
        }
        activityLock.unlock()
    }

    private func flushActivity(_ activityKey: ActivityKey) async {
        let entries: [SessionActivityCacheEntry]?
        let clearToken = clears.token(connectionId: activityKey.connectionId, worktree: activityKey.directory)
        activityLock.lock()
        activityDebounceTasks[activityKey] = nil
        entries = pendingActivity.removeValue(forKey: activityKey)
        activityLock.unlock()
        guard let entries else { return }
        activityLock.lock()
        if lastActivity[activityKey] == entries {
            activityLock.unlock()
            return
        }
        activityLock.unlock()
        let key = SessionCacheKey(
            connectionId: activityKey.connectionId,
            worktree: activityKey.directory,
            kind: .sessionActivity
        )
        do {
            let payload = try SessionCacheCodec.encodeActivity(entries)
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
            // A clear that ran during the write wins.
            guard clears.isCurrent(clearToken, connectionId: key.connectionId, worktree: key.worktree) else {
                await store.evict(.worktree(
                    connectionIdOrUrlPrefix: key.connectionId,
                    worktree: key.worktree,
                    matchByUrlPrefix: false
                ))
                return
            }
            activityLock.lock()
            lastActivity[activityKey] = entries
            activityLock.unlock()
        } catch {
            DiagnosticLog.warning(.session, "encode/commit activity failed: \(error.localizedDescription)")
        }
    }

    /// `connectionID` reads another profile's entry (a switch before its server is bound).
    func seedProjectResolution(worktree: String, connectionID: String?) async -> HomeProjectResolution? {
        guard let connectionId = connectionID ?? connectionIdProvider() else { return nil }
        let key = SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .projectResolution)
        return await Self.readProjectResolution(store: store, key: key)
    }

    func rememberProjectResolution(worktree: String, resolution: HomeProjectResolution) {
        do {
            let payload = try SessionCacheCodec.encodeProjectResolution(resolution)
            queueHomeRecord(worktree: worktree, kind: .projectResolution, payload: payload)
        } catch {
            DiagnosticLog.warning(.session, "encode project resolution failed: \(error.localizedDescription)")
        }
    }

    func seedWorktreeLayout(worktree: String, connectionID: String?) async -> HomeWorktreeLayout? {
        guard let connectionId = connectionID ?? connectionIdProvider() else { return nil }
        let key = SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .worktreeLayout)
        return await Self.readWorktreeLayout(store: store, key: key)
    }

    func rememberWorktreeLayout(worktree: String, layout: HomeWorktreeLayout) {
        do {
            let payload = try SessionCacheCodec.encodeWorktreeLayout(layout)
            queueHomeRecord(worktree: worktree, kind: .worktreeLayout, payload: payload)
        } catch {
            DiagnosticLog.warning(.session, "encode worktree layout failed: \(error.localizedDescription)")
        }
    }

    /// Queues the write under the connection active now; writes land in call order.
    private func queueHomeRecord(worktree: String, kind: SessionCacheKind, payload: String) {
        guard let connectionId = connectionIdProvider() else { return }
        let key = SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: kind)
        homeRecordLock.lock()
        pendingHomeRecords[key] = payload
        let previous = homeRecordWrites
        homeRecordWrites = Task { [weak self] in
            await previous?.value
            await self?.flushHomeRecords()
        }
        homeRecordLock.unlock()
    }

    /// Commits the newest queued record of each key; a write that finds the queue empty does nothing.
    private func flushHomeRecords() async {
        homeRecordLock.lock()
        let queued = pendingHomeRecords
        pendingHomeRecords = [:]
        homeRecordLock.unlock()
        for (key, payload) in queued {
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
        }
    }

    /// Drops the queued Home records `matches` selects, then waits for a write in flight so the
    /// purge that follows evicts its row.
    private func dropPendingHomeRecords(where matches: (SessionCacheKey) -> Bool) async {
        homeRecordLock.lock()
        pendingHomeRecords = pendingHomeRecords.filter { key, _ in !matches(key) }
        let inFlight = homeRecordWrites
        homeRecordLock.unlock()
        await inFlight?.value
    }

    func openMessages(sessionID: String, worktree: String) -> SessionMessageCacheHandle {
        DefaultSessionMessageCacheHandle(
            sessionID: sessionID,
            worktree: worktree,
            store: store,
            connectionIdProvider: connectionIdProvider,
            clock: clock,
            debounceNanoseconds: debounceNanoseconds,
            thumbnails: thumbnails,
            clears: clears,
            onTimelineCommitted: { [weak self] connectionId in
                await self?.boundTimelines(connectionId: connectionId)
            }
        )
    }

    /// Keeps the most recently used transcripts of one connection. Lists, activity and layouts
    /// are never evicted here; thumbnails of evicted transcripts go with them.
    private func boundTimelines(connectionId: String) async {
        let evicted = await store.evictLeastRecentTimelines(
            connectionId: connectionId,
            keeping: Self.maxTimelinesPerConnection
        )
        if !evicted.isEmpty {
            await sweepThumbnails()
        }
    }

    static let maxTimelinesPerConnection = 200

    func purgeWorktree(serverBaseURL: String, worktree: String) async {
        let connection = connectionIdProvider()
        clears.clear(connectionId: connection, worktree: worktree)
        dropActivityMemory { $0.directory == worktree && (connection == nil || $0.connectionId == connection) }
        await dropPendingHomeRecords { $0.worktree == worktree && (connection == nil || $0.connectionId == connection) }
        if let connectionId = connection {
            await store.evict(
                .worktree(
                    connectionIdOrUrlPrefix: connectionId,
                    worktree: worktree,
                    matchByUrlPrefix: false
                )
            )
        }
        await store.evict(
            .worktree(
                connectionIdOrUrlPrefix: CacheUrlNormalizer.connectionIdPrefix(baseURL: serverBaseURL),
                worktree: worktree,
                matchByUrlPrefix: true
            )
        )
        await sweepThumbnails()
    }

    func purgeConnection(profileID: String, serverBaseURL: String) async {
        // Only this server's pending writes: another profile's stay queued under their own key.
        let urlPrefix = CacheUrlNormalizer.connectionIdPrefix(baseURL: serverBaseURL).lowercased()
        let belongs: (String) -> Bool = { connectionId in
            (!profileID.isEmpty && connectionId.caseInsensitiveCompare(profileID) == .orderedSame)
                || connectionId.lowercased().hasPrefix(urlPrefix)
        }
        clears.clear(connectionId: profileID.isEmpty ? nil : profileID, worktree: nil, matching: belongs)
        dropActivityMemory { belongs($0.connectionId) }
        await dropPendingHomeRecords { belongs($0.connectionId) }
        if !profileID.isEmpty {
            await store.evict(.connection(connectionIdOrUrlPrefix: profileID, matchByUrlPrefix: false))
            await thumbnails?.removeConnection(profileID)
        }
        await store.evict(
            .connection(
                connectionIdOrUrlPrefix: CacheUrlNormalizer.connectionIdPrefix(baseURL: serverBaseURL),
                matchByUrlPrefix: true
            )
        )
        // Rows matched by URL prefix have connection ids we cannot hash back; the sweep drops
        // every thumbnail folder whose timeline row is gone.
        await sweepThumbnails()
    }

    func clearAll() async {
        clears.clearEverything()
        dropActivityMemory { _ in true }
        await dropPendingHomeRecords { _ in true }
        await store.evict(.all)
        await thumbnails?.removeAll()
    }

    func maintainThumbnails() async {
        guard let thumbnails else { return }
        await thumbnails.maintain(keeping: await cachedTimelineSessions())
    }

    private func sweepThumbnails() async {
        guard let thumbnails, let sessions = await cachedTimelineSessions() else { return }
        await thumbnails.sweepOrphans(keeping: sessions)
    }

    /// Every cached message timeline (opened sessions included), or nil when the store cannot list them.
    private func cachedTimelineSessions() async -> [(connectionID: String, sessionID: String)]? {
        await store.messageTimelineKeys()?.map { ($0.connectionId, $0.entryId) }
    }

    /// Cancels pending activity flushes and forgets committed activity for the keys selected.
    private func dropActivityMemory(where matches: (ActivityKey) -> Bool) {
        activityLock.lock()
        let keys = Set(lastActivity.keys).union(pendingActivity.keys).union(activityDebounceTasks.keys)
        for key in keys where matches(key) {
            activityDebounceTasks.removeValue(forKey: key)?.cancel()
            pendingActivity.removeValue(forKey: key)
            lastActivity.removeValue(forKey: key)
        }
        activityLock.unlock()
    }

    func estimatedSizeBytes() async -> Int64 {
        await store.report(.all).totalBytes
    }
}

private nonisolated struct ActivityKey: Hashable, Sendable {
    let connectionId: String
    let directory: String
}

/// Counts clears, so a write that started before one can tell and not undo it.
private nonisolated final class SessionCacheClearLedger: @unchecked Sendable {
    struct Token {
        let everything: UInt64
        let connection: UInt64
        let worktree: UInt64
    }

    private struct WorktreeKey: Hashable {
        let connectionId: String
        let worktree: String
    }

    private let lock = NSLock()
    private var everything: UInt64 = 0
    private var connections: [String: UInt64] = [:]
    private var worktrees: [WorktreeKey: UInt64] = [:]
    /// Connection ids seen by a token, so a clear matched by URL prefix can reach them.
    private var knownConnections: Set<String> = []

    func token(connectionId: String, worktree: String) -> Token {
        lock.withLock {
            knownConnections.insert(connectionId)
            return Token(
                everything: everything,
                connection: connections[connectionId] ?? 0,
                worktree: worktrees[WorktreeKey(connectionId: connectionId, worktree: worktree)] ?? 0
            )
        }
    }

    func isCurrent(_ token: Token, connectionId: String, worktree: String) -> Bool {
        lock.withLock {
            token.everything == everything
                && token.connection == (connections[connectionId] ?? 0)
                && token.worktree == (worktrees[WorktreeKey(connectionId: connectionId, worktree: worktree)] ?? 0)
        }
    }

    func clear(connectionId: String?, worktree: String?, matching: ((String) -> Bool)? = nil) {
        lock.withLock {
            var targets = Set<String>()
            if let connectionId { targets.insert(connectionId) }
            if let matching { targets.formUnion(knownConnections.filter(matching)) }
            if connectionId == nil, matching == nil { targets = knownConnections }
            for target in targets {
                if let worktree {
                    worktrees[WorktreeKey(connectionId: target, worktree: worktree), default: 0] &+= 1
                } else {
                    connections[target, default: 0] &+= 1
                }
            }
        }
    }

    func clearEverything() {
        lock.withLock { everything &+= 1 }
    }
}

private nonisolated final class DefaultSessionMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let sessionID: String
    private let worktree: String
    private let store: any SessionCacheStore
    private let connectionIdProvider: @Sendable () -> String?
    private let clock: @Sendable () -> Int64
    private let debounceNanoseconds: UInt64
    private let thumbnails: ToolThumbnailStore?
    private let clears: SessionCacheClearLedger
    private let onTimelineCommitted: @Sendable (String) async -> Void

    private let lock = NSLock()
    /// Fixed the first time a connection is known: this transcript belongs to that server.
    private var boundKey: SessionCacheKey?
    private var pending: [Message]?
    private var pendingClearToken: SessionCacheClearLedger.Token?
    private var debounceTask: Task<Void, Never>?
    private var closed = false

    init(
        sessionID: String,
        worktree: String,
        store: any SessionCacheStore,
        connectionIdProvider: @escaping @Sendable () -> String?,
        clock: @escaping @Sendable () -> Int64,
        debounceNanoseconds: UInt64,
        thumbnails: ToolThumbnailStore?,
        clears: SessionCacheClearLedger,
        onTimelineCommitted: @escaping @Sendable (String) async -> Void
    ) {
        self.thumbnails = thumbnails
        self.clears = clears
        self.onTimelineCommitted = onTimelineCommitted
        self.sessionID = sessionID
        self.worktree = worktree
        self.store = store
        self.connectionIdProvider = connectionIdProvider
        self.clock = clock
        self.debounceNanoseconds = debounceNanoseconds
        if let connectionId = connectionIdProvider() {
            boundKey = Self.key(connectionId: connectionId, worktree: worktree, sessionID: sessionID)
        }
    }

    private static func key(connectionId: String, worktree: String, sessionID: String) -> SessionCacheKey {
        SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .messageTimeline, entryId: sessionID)
    }

    private func keyOrNil() -> SessionCacheKey? {
        lock.lock()
        defer { lock.unlock() }
        return keyLocked()
    }

    private func keyLocked() -> SessionCacheKey? {
        if let boundKey { return boundKey }
        guard let connectionId = connectionIdProvider() else { return nil }
        let key = Self.key(connectionId: connectionId, worktree: worktree, sessionID: sessionID)
        boundKey = key
        return key
    }

    func seed() async -> [Message]? {
        guard let key = keyOrNil() else { return nil }
        await store.retain(key: key, reason: .opened, atEpochMs: clock())
        guard let record = await store.load(key: key), !record.value.payloadJSON.isEmpty else {
            return nil
        }
        // A timeline that kept no messages (its newest was over the budget) is a miss.
        guard let timeline = SessionCacheCodec.decodeMessageTimeline(record.value.payloadJSON),
              !timeline.messages.isEmpty else { return nil }
        guard let thumbnails, !timeline.thumbnailImageIDs.isEmpty else { return timeline.messages }
        return await thumbnails.attach(
            connectionID: key.connectionId,
            sessionID: sessionID,
            messages: timeline.messages,
            imageIDs: timeline.thumbnailImageIDs
        )
    }

    func note(messages: [Message]) {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, let key = keyLocked() else { return }
        pendingClearToken = clears.token(connectionId: key.connectionId, worktree: key.worktree)
        pending = messages
        debounceTask?.cancel()
        let delay = debounceNanoseconds
        debounceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            await self?.flushPending(cancelDebounceTask: false)
        }
    }

    func flush() async {
        await flushPending(cancelDebounceTask: true)
    }

    func close() {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        debounceTask?.cancel()
        debounceTask = nil
        lock.unlock()
        Task {
            await flushPending(cancelDebounceTask: false)
        }
    }

    private func flushPending(cancelDebounceTask: Bool) async {
        let messages: [Message]?
        lock.lock()
        if cancelDebounceTask {
            debounceTask?.cancel()
            debounceTask = nil
        } else {
            debounceTask = nil
        }
        messages = pending
        pending = nil
        let clearToken = pendingClearToken
        pendingClearToken = nil
        let boundKey = keyLocked()
        lock.unlock()

        guard let messages, let key = boundKey, let clearToken else { return }
        // Cleared while this waited: the clear wins.
        guard clears.isCurrent(clearToken, connectionId: key.connectionId, worktree: key.worktree) else { return }
        do {
            var thumbnailImageIDs = Set<String>()
            if let thumbnails {
                thumbnailImageIDs = await thumbnails.persist(
                    connectionID: key.connectionId,
                    sessionID: sessionID,
                    messages: messages
                )
            }
            let payload = try SessionCacheCodec.encodeMessages(messages, thumbnailImageIDs: thumbnailImageIDs)
            // Thumbnails written just now are orphans if this stops; the launch sweep drops them.
            guard clears.isCurrent(clearToken, connectionId: key.connectionId, worktree: key.worktree) else { return }
            await store.retain(key: key, reason: .opened, atEpochMs: clock())
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
            guard clears.isCurrent(clearToken, connectionId: key.connectionId, worktree: key.worktree) else {
                await store.evict(.worktree(
                    connectionIdOrUrlPrefix: key.connectionId,
                    worktree: key.worktree,
                    matchByUrlPrefix: false
                ))
                return
            }
            await onTimelineCommitted(key.connectionId)
        } catch {
            DiagnosticLog.warning(.session, "encode/commit messages failed: \(error.localizedDescription)")
        }
    }
}
