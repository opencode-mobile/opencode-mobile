import Foundation

nonisolated final class DefaultSessionCache: SessionCache, @unchecked Sendable {
    private let store: any SessionCacheStore
    private let connectionIdProvider: @Sendable () -> String?
    private let clock: @Sendable () -> Int64
    private let debounceNanoseconds: UInt64
    private let activityDebounceNanoseconds: UInt64
    private let activityLock = NSLock()
    private var pendingActivity: [String: [SessionActivityCacheEntry]] = [:]
    private var lastActivity: [String: [SessionActivityCacheEntry]] = [:]
    private var activityDebounceTasks: [String: Task<Void, Never>] = [:]

    init(
        store: any SessionCacheStore,
        connectionIdProvider: @escaping @Sendable () -> String?,
        clock: @escaping @Sendable () -> Int64 = {
            Int64(Date().timeIntervalSince1970 * 1000)
        },
        debounceMilliseconds: UInt64 = 3_000,
        activityDebounceMilliseconds: UInt64 = SessionActivityPolicy.activityRememberDebounceMs
    ) {
        self.store = store
        self.connectionIdProvider = connectionIdProvider
        self.clock = clock
        self.debounceNanoseconds = debounceMilliseconds * 1_000_000
        self.activityDebounceNanoseconds = activityDebounceMilliseconds * 1_000_000
    }

    func seedSessions(worktree: String) async -> [Session]? {
        guard let connectionId = connectionIdProvider() else { return nil }
        let key = SessionCacheKey(connectionId: connectionId, worktree: worktree, kind: .sessionList)
        guard let record = await store.load(key: key) else { return nil }
        return SessionCacheCodec.decodeSessions(record.value.payloadJSON)
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
        guard let record = await store.load(key: key) else { return nil }
        guard var entries = SessionCacheCodec.decodeActivity(record.value.payloadJSON) else { return nil }
        let cutoff = clock() - SessionActivityPolicy.cachedActivityTtlMs
        entries = entries.filter { $0.observedAtMs >= cutoff }
        activityLock.lock()
        lastActivity[directory] = entries
        activityLock.unlock()
        return entries
    }

    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async {
        guard connectionIdProvider() != nil else { return }
        let normalized = entries.sorted { $0.sessionId < $1.sessionId }
        activityLock.lock()
        if lastActivity[directory] == normalized, pendingActivity[directory] == nil {
            activityLock.unlock()
            return
        }
        if pendingActivity[directory] == normalized {
            activityLock.unlock()
            return
        }
        pendingActivity[directory] = normalized
        activityDebounceTasks[directory]?.cancel()
        let delay = activityDebounceNanoseconds
        if delay == 0 {
            // Flush inline and only inline: a parallel task could take the
            // pending entries first and still be committing when the caller
            // returns, so a read straight after would miss them.
            activityDebounceTasks[directory] = nil
            activityLock.unlock()
            await flushActivity(directory: directory)
            return
        }
        activityDebounceTasks[directory] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            await self?.flushActivity(directory: directory)
        }
        activityLock.unlock()
    }

    private func flushActivity(directory: String) async {
        let entries: [SessionActivityCacheEntry]?
        activityLock.lock()
        activityDebounceTasks[directory] = nil
        entries = pendingActivity.removeValue(forKey: directory)
        activityLock.unlock()
        guard let entries, let connectionId = connectionIdProvider() else { return }
        activityLock.lock()
        if lastActivity[directory] == entries {
            activityLock.unlock()
            return
        }
        activityLock.unlock()
        let key = SessionCacheKey(connectionId: connectionId, worktree: directory, kind: .sessionActivity)
        do {
            let payload = try SessionCacheCodec.encodeActivity(entries)
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
            activityLock.lock()
            lastActivity[directory] = entries
            activityLock.unlock()
        } catch {
            DiagnosticLog.warning(.session, "encode/commit activity failed: \(error.localizedDescription)")
        }
    }

    func openMessages(sessionID: String, worktree: String) -> SessionMessageCacheHandle {
        DefaultSessionMessageCacheHandle(
            sessionID: sessionID,
            worktree: worktree,
            store: store,
            connectionIdProvider: connectionIdProvider,
            clock: clock,
            debounceNanoseconds: debounceNanoseconds
        )
    }

    func purgeWorktree(serverBaseURL: String, worktree: String) async {
        dropActivityMemory(directory: worktree)
        if let connectionId = connectionIdProvider() {
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
    }

    func purgeConnection(profileID: String, serverBaseURL: String) async {
        // A pending activity flush would otherwise commit the removed server's activity
        // under whichever profile is active when the debounce fires.
        dropActivityMemory(directory: nil)
        if !profileID.isEmpty {
            await store.evict(.connection(connectionIdOrUrlPrefix: profileID, matchByUrlPrefix: false))
        }
        await store.evict(
            .connection(
                connectionIdOrUrlPrefix: CacheUrlNormalizer.connectionIdPrefix(baseURL: serverBaseURL),
                matchByUrlPrefix: true
            )
        )
    }

    func clearAll() async {
        dropActivityMemory(directory: nil)
        await store.evict(.all)
    }

    /// Cancels pending activity flushes and forgets committed activity for `directory`, or for
    /// every directory when `nil`.
    private func dropActivityMemory(directory: String?) {
        activityLock.lock()
        let directories = directory.map { [$0] }
            ?? Array(Set(lastActivity.keys).union(pendingActivity.keys).union(activityDebounceTasks.keys))
        for directory in directories {
            activityDebounceTasks.removeValue(forKey: directory)?.cancel()
            pendingActivity.removeValue(forKey: directory)
            lastActivity.removeValue(forKey: directory)
        }
        activityLock.unlock()
    }

    func estimatedSizeBytes() async -> Int64 {
        await store.report(.all).totalBytes
    }
}

private nonisolated final class DefaultSessionMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    private let sessionID: String
    private let worktree: String
    private let store: any SessionCacheStore
    private let connectionIdProvider: @Sendable () -> String?
    private let clock: @Sendable () -> Int64
    private let debounceNanoseconds: UInt64

    private let lock = NSLock()
    private var pending: [Message]?
    private var debounceTask: Task<Void, Never>?
    private var closed = false

    init(
        sessionID: String,
        worktree: String,
        store: any SessionCacheStore,
        connectionIdProvider: @escaping @Sendable () -> String?,
        clock: @escaping @Sendable () -> Int64,
        debounceNanoseconds: UInt64
    ) {
        self.sessionID = sessionID
        self.worktree = worktree
        self.store = store
        self.connectionIdProvider = connectionIdProvider
        self.clock = clock
        self.debounceNanoseconds = debounceNanoseconds
    }

    private func keyOrNil() -> SessionCacheKey? {
        guard let connectionId = connectionIdProvider() else { return nil }
        return SessionCacheKey(
            connectionId: connectionId,
            worktree: worktree,
            kind: .messageTimeline,
            entryId: sessionID
        )
    }

    func seed() async -> [Message]? {
        guard let key = keyOrNil() else { return nil }
        await store.retain(key: key, reason: .opened, atEpochMs: clock())
        guard let record = await store.load(key: key), !record.value.payloadJSON.isEmpty else {
            return nil
        }
        return SessionCacheCodec.decodeMessages(record.value.payloadJSON)
    }

    func note(messages: [Message]) {
        lock.lock()
        defer { lock.unlock() }
        guard !closed, keyOrNil() != nil else { return }
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
        lock.unlock()

        guard let messages, let key = keyOrNil() else { return }
        do {
            let payload = try SessionCacheCodec.encodeMessages(messages)
            await store.retain(key: key, reason: .opened, atEpochMs: clock())
            await store.commit(
                key: key,
                value: SessionCacheValue(payloadJSON: payload, schemaVersion: SessionCacheCodec.schemaVersion),
                meta: SessionCacheCommitMeta(syncedAtEpochMs: clock())
            )
        } catch {
            DiagnosticLog.warning(.session, "encode/commit messages failed: \(error.localizedDescription)")
        }
    }
}
