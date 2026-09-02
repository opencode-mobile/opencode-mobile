import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let eventAdapter: EventDomainAdapter
    private let worldSignals: any WorldSignals
    private let invalidationBus: StoreInvalidationBus
    private let activeDirectories = ActiveDirectorySet()
    private let pendingBusy = PendingBusySet()
    private let mutationBus = StatusMutationBus()
    private var cache: [String: SharedLiveObservation<[String: SessionStatus]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter = EventDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.eventAdapter = eventAdapter
        self.worldSignals = worldSignals
        invalidationBus = StoreInvalidationBus(ackTimeoutMs: invalidationAckTimeoutMs)
    }

    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let eventAdapter = eventAdapter
            let worldSignals = worldSignals
            let invalidationBus = invalidationBus
            let activeDirectories = activeDirectories
            let pendingBusy = pendingBusy
            let mutationBus = mutationBus
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runStatusLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        eventAdapter: eventAdapter,
                        worldSignals: worldSignals,
                        invalidationBus: invalidationBus,
                        activeDirectories: activeDirectories,
                        pendingBusy: pendingBusy,
                        mutationBus: mutationBus,
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

    func markBusy(directory: String, sessionID: String) {
        pendingBusy.insert(directory: directory, sessionID: sessionID)
        mutationBus.yield(.markBusy(directory: directory, sessionID: sessionID))
    }

    func clearLocalBusy(directory: String, sessionID: String) {
        pendingBusy.remove(directory: directory, sessionID: sessionID)
        mutationBus.yield(.clearLocalBusy(directory: directory, sessionID: sessionID))
    }

    func markIdle(directory: String, sessionID: String) {
        pendingBusy.remove(directory: directory, sessionID: sessionID)
        mutationBus.yield(.markIdle(directory: directory, sessionID: sessionID))
    }

    func clearCache() {
        // Finish before dropping: a dropped-but-unfinished observation leaves every
        // subscriber suspended forever instead of letting it re-subscribe.
        cache.values.forEach { $0.finish() }
        cache.removeAll()
        pendingBusy.removeAll()
    }

    private nonisolated static func runStatusLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter,
        worldSignals: any WorldSignals,
        invalidationBus: StoreInvalidationBus,
        activeDirectories: ActiveDirectorySet,
        pendingBusy: PendingBusySet,
        mutationBus: StatusMutationBus,
        yield: @escaping @Sendable ([String: SessionStatus]) -> Void
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
        let serverBox = StatusBox()
        let currentBox = StatusBox()
        let stale = StaleBit()
        // Single-flight plus revision gate for this observation. Every mutation
        // of the authoritative `server` map that does not come from a REST commit
        // bumps it, so a REST response captured before such a mutation is dropped
        // instead of resurrecting the pre-patch snapshot.
        let gate = LiveStoreRefreshGate()

        @Sendable func overlayPending(
            _ base: [String: SessionStatus],
            confirmFrom: [String: SessionStatus]? = nil
        ) -> [String: SessionStatus] {
            let confirm = confirmFrom ?? base
            var result = base
            for sessionID in pendingBusy.snapshot(directory: directory) {
                let restType = confirm[sessionID]?.raw["type"]
                if restType == "busy" || restType == "retry" {
                    pendingBusy.remove(directory: directory, sessionID: sessionID)
                } else {
                    result[sessionID] = SessionStatus(sessionID: sessionID, raw: ["type": "busy"])
                }
            }
            return result
        }

        @Sendable func emitOverlay(_ base: [String: SessionStatus]) {
            let next = overlayPending(base, confirmFrom: [:])
            guard next != currentBox.snapshot() else { return }
            currentBox.replace(next)
            yield(next)
        }

        @Sendable func commitLoaded(_ loaded: [String: SessionStatus], emptyActiveGuard: Bool = false) {
            // Empty active maps are a between-step gap on v2, not idle.
            // True idle is session.idle / execution.*. Overlay pending
            // onto the kept server map; do not skip overlay because
            // pending is set.
            if emptyActiveGuard, loaded.isEmpty {
                let server = serverBox.snapshot()
                if !server.isEmpty {
                    emitOverlay(server)
                    return
                }
            }
            serverBox.replace(loaded)
            let next = overlayPending(loaded)
            currentBox.replace(next)
            yield(next)
        }

        /// Reloads statuses and commits them, unless an SSE patch (or a local
        /// `markIdle`) moved the server map while the GET was in flight — that
        /// patch is strictly newer than the snapshot this response describes.
        @discardableResult
        @Sendable func reloadFromServer(
            reason: String,
            force: Bool,
            emptyActiveGuard: Bool = false
        ) async -> LiveStoreRefreshOutcome {
            if !force, !stale.isSet {
                DiagnosticLog.info(
                    .session,
                    "statusLiveStore skip directory=\(directory) reason=\(reason)"
                )
                return .failed
            }
            let outcome = await gate.refresh(
                load: { () async -> [String: SessionStatus]? in
                    guard let loaded = await Self.loadStatuses(
                        directory: directory,
                        sessionRepository: sessionRepository
                    ) else { return nil }
                    stale.clear()
                    DiagnosticLog.info(
                        .session,
                        "statusLiveStore refresh directory=\(directory) reason=\(reason)"
                    )
                    return loaded
                },
                commit: { loaded in
                    commitLoaded(loaded, emptyActiveGuard: emptyActiveGuard)
                }
            )
            if outcome == .discarded {
                DiagnosticLog.info(
                    .session,
                    "statusLiveStore discard directory=\(directory) reason=\(reason) "
                        + "cause=superseded-by-live-patch"
                )
            }
            return outcome
        }

        let mutationTask = Task {
            for await mutation in mutationBus.events() {
                guard mutation.directory == directory else { continue }
                switch mutation {
                case let .markBusy(_, sessionID):
                    guard pendingBusy.contains(directory: directory, sessionID: sessionID) else {
                        continue
                    }
                    emitOverlay(serverBox.snapshot())
                case .clearLocalBusy:
                    emitOverlay(serverBox.snapshot())
                case let .markIdle(_, sessionID):
                    var server = serverBox.snapshot()
                    server.removeValue(forKey: sessionID)
                    // Mutates the authoritative map: a REST snapshot taken before
                    // this would put the session back.
                    serverBox.replace(server)
                    await gate.bump()
                    emitOverlay(server)
                }
            }
        }
        defer { mutationTask.cancel() }

        await reloadFromServer(reason: StoreRefreshReason.initialSubscribe, force: true)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        stale.mark()
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    await reloadFromServer(reason: reason, force: false)
                }
            }
            group.addTask {
                for await invalidation in invalidations {
                    if let key = invalidation.key, key != directory { continue }
                    // Ack on every exit from the iteration, including a torn-down or
                    // failed reload: the ack releases the caller's spinner, it is not
                    // a "data is fresh" signal.
                    let ack = invalidation.ack
                    defer { ack?() }
                    stale.mark()
                    await reloadFromServer(reason: invalidation.reason, force: true)
                }
            }
            group.addTask {
                var reconnectTask: Task<Void, Never>?
                defer { reconnectTask?.cancel() }
                for await event in eventStream.events() {
                    if event.type == "stream.reconnected" || event.type == "server.connected" {
                        reconnectTask?.cancel()
                        let reason = event.type
                        reconnectTask = Task {
                            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                            guard !Task.isCancelled else { return }
                            await reloadFromServer(reason: reason, force: true)
                        }
                        continue
                    }
                    if event.type == "session.active.snapshot" {
                        reconnectTask?.cancel()
                        let reason = event.type
                        reconnectTask = Task {
                            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                            guard !Task.isCancelled else { return }
                            await reloadFromServer(
                                reason: reason,
                                force: true,
                                emptyActiveGuard: true
                            )
                        }
                        continue
                    }
                    guard event.triggersStatusRefresh else { continue }
                    let server = serverBox.snapshot()
                    if let nextServer = applyStatusEvent(
                        directory: directory,
                        current: server,
                        event: event,
                        eventAdapter: eventAdapter,
                        pendingBusy: pendingBusy
                    ) {
                        // A live patch outranks any REST snapshot that was captured
                        // before it: bump so an in-flight reload cannot write the
                        // pre-patch map back.
                        serverBox.replace(nextServer)
                        await gate.bump()
                    }
                    emitOverlay(serverBox.snapshot())
                }
            }
            await group.next()
            group.cancelAll()
        }
    }

    private nonisolated static func loadStatuses(
        directory: String,
        sessionRepository: any SessionRepository
    ) async -> [String: SessionStatus]? {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: directory) else {
            return nil
        }
        return Dictionary(uniqueKeysWithValues: statuses.map { ($0.sessionID, $0) })
    }

    private nonisolated static func applyStatusEvent(
        directory: String,
        current: [String: SessionStatus],
        event: OpencodeEvent,
        eventAdapter: EventDomainAdapter,
        pendingBusy: PendingBusySet
    ) -> [String: SessionStatus]? {
        switch event.type {
        case "session.status":
            guard let status = eventAdapter.sessionStatus(from: event) else { return nil }
            if status.raw["type"] == "idle" {
                pendingBusy.remove(directory: directory, sessionID: status.sessionID)
                guard current[status.sessionID] != nil else { return nil }
                var next = current
                next.removeValue(forKey: status.sessionID)
                return next
            }
            pendingBusy.remove(directory: directory, sessionID: status.sessionID)
            var next = current
            next[status.sessionID] = status
            return next
        case "session.idle":
            guard let sessionID = event.sessionID else { return nil }
            pendingBusy.remove(directory: directory, sessionID: sessionID)
            guard current[sessionID] != nil else { return nil }
            var next = current
            next.removeValue(forKey: sessionID)
            return next
        default:
            return nil
        }
    }
}

private nonisolated enum StatusMutation: Sendable {
    case markBusy(directory: String, sessionID: String)
    case clearLocalBusy(directory: String, sessionID: String)
    case markIdle(directory: String, sessionID: String)

    var directory: String {
        switch self {
        case let .markBusy(directory, _), let .clearLocalBusy(directory, _), let .markIdle(directory, _):
            directory
        }
    }
}

private nonisolated final class StatusMutationBus: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<StatusMutation>.Continuation] = [:]
    private var buffer: [StatusMutation] = []

    func events() -> AsyncStream<StatusMutation> {
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

    func yield(_ mutation: StatusMutation) {
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

private nonisolated final class PendingBusySet: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Set<String>] = [:]

    func insert(directory: String, sessionID: String) {
        lock.lock()
        var bucket = values[directory] ?? []
        bucket.insert(sessionID)
        values[directory] = bucket
        lock.unlock()
    }

    func remove(directory: String, sessionID: String) {
        lock.lock()
        if var bucket = values[directory] {
            bucket.remove(sessionID)
            values[directory] = bucket
        }
        lock.unlock()
    }

    func contains(directory: String, sessionID: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return values[directory]?.contains(sessionID) ?? false
    }

    func snapshot(directory: String) -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return values[directory] ?? []
    }

    func removeAll() {
        lock.lock()
        values.removeAll()
        lock.unlock()
    }
}

private nonisolated final class StatusBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [String: SessionStatus] = [:]

    func snapshot() -> [String: SessionStatus] {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func replace(_ next: [String: SessionStatus]) {
        lock.lock()
        value = next
        lock.unlock()
    }
}
