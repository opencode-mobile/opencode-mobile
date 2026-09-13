import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let eventAdapter: EventDomainAdapter
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let invalidationAckTimeoutMs: Int
    private let activeDirectories = ActiveDirectorySet()
    private let pendingBusy = PendingBusySet()
    private let mutationBus = StatusMutationBus()
    private var cache: [String: SharedLiveObservation<[String: SessionStatus]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter = EventDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil,
        invalidationAckTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.eventAdapter = eventAdapter
        self.worldSignals = worldSignals
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
        self.invalidationAckTimeoutMs = invalidationAckTimeoutMs
    }

    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) async {
        let keys: [ProjectionKey]
        if let directory {
            keys = activeDirectories.contains(directory) ? [.sessionStatuses(directory: directory)] : []
        } else {
            keys = activeDirectories.snapshot().map { .sessionStatuses(directory: $0) }
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
        let syncCoordinator = syncCoordinator
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
                    syncCoordinator: syncCoordinator,
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
        syncCoordinator: any SyncCoordinator,
        activeDirectories: ActiveDirectorySet,
        pendingBusy: PendingBusySet,
        mutationBus: StatusMutationBus,
        yield: @escaping @Sendable ([String: SessionStatus]) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let serverBox = StatusBox()
        let currentBox = StatusBox()
        let gate = LiveStoreRefreshGate()
        let dropUnconfirmedPendingAfterBackgroundCatchUp = DropPendingFlag()
        let key = ProjectionKey.sessionStatuses(directory: directory)

        @Sendable func overlayPending(
            _ base: [String: SessionStatus],
            confirmFrom: [String: SessionStatus]? = nil,
            dropUnconfirmedPending: Bool = false
        ) -> [String: SessionStatus] {
            let confirm = confirmFrom ?? base
            var result = base
            for sessionID in pendingBusy.snapshot(directory: directory) {
                let restType = confirm[sessionID]?.raw["type"]
                if restType == "busy" || restType == "retry" {
                    pendingBusy.remove(directory: directory, sessionID: sessionID)
                } else if dropUnconfirmedPending {
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

        @Sendable func commitLoaded(
            _ loaded: [String: SessionStatus],
            emptyActiveGuard: Bool = false,
            dropUnconfirmedPending: Bool = false
        ) {
            if emptyActiveGuard, loaded.isEmpty {
                let server = serverBox.snapshot()
                if !server.isEmpty {
                    emitOverlay(server)
                    return
                }
            }
            serverBox.replace(loaded)
            let next = overlayPending(loaded, dropUnconfirmedPending: dropUnconfirmedPending)
            if dropUnconfirmedPending {
                dropUnconfirmedPendingAfterBackgroundCatchUp.clear()
            }
            currentBox.replace(next)
            yield(next)
        }

        @Sendable func loadAndCommit(emptyActiveGuard: Bool = false) async throws {
            let outcome = await gate.refresh(
                load: { () async -> [String: SessionStatus]? in
                    await Self.loadStatuses(
                        directory: directory,
                        sessionRepository: sessionRepository
                    )
                },
                commit: { loaded in
                    DiagnosticLog.info(.session, "statusLiveStore refresh directory=\(directory)")
                    commitLoaded(
                        loaded,
                        emptyActiveGuard: emptyActiveGuard,
                        dropUnconfirmedPending: dropUnconfirmedPendingAfterBackgroundCatchUp.isArmed
                    )
                }
            )
            if outcome == .discarded {
                DiagnosticLog.info(
                    .session,
                    "statusLiveStore discard directory=\(directory) cause=superseded-by-live-patch"
                )
            }
            if outcome == .failed {
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: { try await loadAndCommit() })
        syncCoordinator.visible(key, true)
        defer {
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
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

        await syncCoordinator.ensureFresh(key)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await occupancy in worldSignals.occupancyUpdates() {
                    if occupancy == .stopped {
                        dropUnconfirmedPendingAfterBackgroundCatchUp.arm()
                    }
                }
            }
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal == .backgroundStop {
                        dropUnconfirmedPendingAfterBackgroundCatchUp.arm()
                    }
                }
            }
            group.addTask {
                var snapshotTask: Task<Void, Never>?
                defer { snapshotTask?.cancel() }
                for await event in eventStream.events() {
                    if event.type == "session.active.snapshot" {
                        snapshotTask?.cancel()
                        snapshotTask = Task {
                            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                            guard !Task.isCancelled else { return }
                            try? await loadAndCommit(emptyActiveGuard: true)
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

private nonisolated final class DropPendingFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var armed = false

    var isArmed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return armed
    }

    func arm() {
        lock.lock()
        armed = true
        lock.unlock()
    }

    func clear() {
        lock.lock()
        armed = false
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
