import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let eventAdapter: EventDomainAdapter
    private let worldSignals: any WorldSignals
    private let invalidationBus = StoreInvalidationBus()
    private var cache: [String: SharedLiveObservation<[String: SessionStatus]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter = EventDomainAdapter(),
        worldSignals: any WorldSignals = SilentWorldSignals.shared
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.eventAdapter = eventAdapter
        self.worldSignals = worldSignals
    }

    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let eventAdapter = eventAdapter
            let worldSignals = worldSignals
            let invalidationBus = invalidationBus
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runStatusLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        eventAdapter: eventAdapter,
                        worldSignals: worldSignals,
                        invalidationBus: invalidationBus,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    func invalidate(directory: String?) {
        invalidationBus.invalidate(key: directory, reason: StoreRefreshReason.pullToRefresh)
    }

    func clearCache() {
        cache.removeAll()
    }

    private nonisolated static func runStatusLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter,
        worldSignals: any WorldSignals,
        invalidationBus: StoreInvalidationBus,
        yield: @escaping @Sendable ([String: SessionStatus]) -> Void
    ) async {
        let currentBox = StatusBox()
        let stale = StaleBit()

        func reloadFromServer(reason: String, force: Bool) async -> [String: SessionStatus]? {
            if !force, !stale.isSet {
                DiagnosticLog.info(
                    .session,
                    "statusLiveStore skip directory=\(directory) reason=\(reason)"
                )
                return nil
            }
            guard let loaded = await loadStatuses(
                directory: directory,
                sessionRepository: sessionRepository
            ) else { return nil }
            stale.clear()
            DiagnosticLog.info(
                .session,
                "statusLiveStore refresh directory=\(directory) reason=\(reason)"
            )
            return loaded
        }

        if let statuses = await reloadFromServer(
            reason: StoreRefreshReason.initialSubscribe,
            force: true
        ) {
            currentBox.replace(statuses)
            yield(statuses)
        }

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        stale.mark()
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    if let statuses = await reloadFromServer(reason: reason, force: false) {
                        currentBox.replace(statuses)
                        yield(statuses)
                    }
                }
            }
            group.addTask {
                for await invalidation in invalidationBus.events() {
                    if let key = invalidation.key, key != directory { continue }
                    stale.mark()
                    if let statuses = await reloadFromServer(
                        reason: invalidation.reason,
                        force: true
                    ) {
                        currentBox.replace(statuses)
                        yield(statuses)
                    }
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
                            if let statuses = await reloadFromServer(reason: reason, force: true) {
                                currentBox.replace(statuses)
                                yield(statuses)
                            }
                        }
                        continue
                    }
                    if event.type == "session.active.snapshot" {
                        reconnectTask?.cancel()
                        let reason = event.type
                        reconnectTask = Task {
                            try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                            guard !Task.isCancelled else { return }
                            if let statuses = await reloadFromServer(reason: reason, force: true) {
                                // Empty active maps are a between-step gap on v2, not idle.
                                // True idle is session.idle / execution.*.
                                if statuses.isEmpty, !currentBox.snapshot().isEmpty { return }
                                currentBox.replace(statuses)
                                yield(statuses)
                            }
                        }
                        continue
                    }
                    guard event.triggersStatusRefresh else { continue }
                    let current = currentBox.snapshot()
                    guard let next = applyStatusEvent(
                        current: current,
                        event: event,
                        eventAdapter: eventAdapter
                    ), next != current
                    else { continue }
                    currentBox.replace(next)
                    yield(next)
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
        current: [String: SessionStatus],
        event: OpencodeEvent,
        eventAdapter: EventDomainAdapter
    ) -> [String: SessionStatus]? {
        switch event.type {
        case "session.status":
            guard let status = eventAdapter.sessionStatus(from: event) else { return nil }
            if status.raw["type"] == "idle" {
                guard current[status.sessionID] != nil else { return nil }
                var next = current
                next.removeValue(forKey: status.sessionID)
                return next
            }
            var next = current
            next[status.sessionID] = status
            return next
        case "session.idle":
            guard let sessionID = event.sessionID, current[sessionID] != nil else { return nil }
            var next = current
            next.removeValue(forKey: sessionID)
            return next
        default:
            return nil
        }
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
