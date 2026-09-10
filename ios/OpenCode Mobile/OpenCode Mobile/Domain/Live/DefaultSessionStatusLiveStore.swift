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
        let stale = Array(cache.values)
        cache.removeAll()
        pendingBusy.removeAll()
        stale.forEach { $0.finish() }
    }

    private func observation(for directory: String) -> SharedLiveObservation<[String: SessionStatus]> {
        if let existing = cache[directory], !existing.isTerminated {
            return existing
        }
        let sessionRepository = sessionRepository
        let eventStream = eventStream
        let eventAdapter = eventAdapter
        let worldSignals = worldSignals
        let invalidationBus = invalidationBus
        let activeDirectories = activeDirectories
        let pendingBusy = pendingBusy
        let mutationBus = mutationBus
        let observation = SharedLiveObservation<[String: SessionStatus]> { yield in
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
        cache[directory] = observation
        return observation
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
        let invalidations = invalidationBus.events()
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let serverBox = StatusBox()
        let currentBox = StatusBox()
        let liveConnection = eventStream.asLiveConnection()
        let authority = GapAuthority()
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

        @discardableResult
        @Sendable func reloadFromServer(
            reason: String,
            force: Bool,
            emptyActiveGuard: Bool = false
        ) async -> LiveStoreRefreshOutcome {
            let snapshot = liveConnection.snapshot
            if !force {
                guard canCatchUp(occupancy: worldSignals.occupancy, snapshot: snapshot) else {
                    DiagnosticLog.info(
                        .session,
                        "statusLiveStore skip directory=\(directory) reason=\(reason)"
                    )
                    return .failed
                }
            }
            let generation: UInt64
            if force {
                generation = authority.captureBegin(snapshot)
            } else {
                guard let started = authority.tryBegin(snapshot) else {
                    DiagnosticLog.info(
                        .session,
                        "statusLiveStore skip directory=\(directory) reason=\(reason)"
                    )
                    return .failed
                }
                generation = started
            }
            let outcome = await gate.refresh(
                load: { () async -> [String: SessionStatus]? in
                    await Self.loadStatuses(
                        directory: directory,
                        sessionRepository: sessionRepository
                    )
                },
                commit: { loaded in
                    DiagnosticLog.info(
                        .session,
                        "statusLiveStore refresh directory=\(directory) reason=\(reason)"
                    )
                    commitLoaded(loaded, emptyActiveGuard: emptyActiveGuard)
                }
            )
            switch outcome {
            case .committed:
                authority.acknowledge(generation)
                if authority.needsReconcile(liveConnection.snapshot) {
                    _ = await reloadFromServer(reason: StoreRefreshReason.streamReconnected, force: false)
                }
            case .failed, .discarded:
                authority.fail(generation)
            }
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
                        authority.invalidate(liveConnection.snapshot)
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    await reloadFromServer(reason: reason, force: false)
                }
            }
            group.addTask {
                for await _ in liveConnection.snapshotUpdates() {
                    await reloadFromServer(reason: StoreRefreshReason.streamReconnected, force: false)
                }
            }
            group.addTask {
                for await invalidation in invalidations {
                    if let key = invalidation.key, key != directory { continue }
                    let ack = invalidation.ack
                    defer { ack?() }
                    authority.invalidate(liveConnection.snapshot)
                    await reloadFromServer(reason: invalidation.reason, force: false)
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
                            await reloadFromServer(reason: reason, force: false)
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
