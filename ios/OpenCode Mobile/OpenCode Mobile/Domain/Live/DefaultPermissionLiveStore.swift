import Foundation

@MainActor
final class DefaultPermissionLiveStore: PermissionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let invalidationBus = StoreInvalidationBus()
    private var cache: [String: SharedLiveObservation<[PermissionRequest]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
    }

    func observePermissions(directory: String) -> AsyncStream<[PermissionRequest]> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) {
        invalidationBus.invalidate(key: directory, reason: StoreRefreshReason.pullToRefresh)
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
        let worldSignals = worldSignals
        let invalidationBus = invalidationBus
        let observation = SharedLiveObservation<[PermissionRequest]> { yield in
            Task.detached {
                await Self.runLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    worldSignals: worldSignals,
                    invalidationBus: invalidationBus,
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
        worldSignals: any WorldSignals,
        invalidationBus: StoreInvalidationBus,
        yield: @escaping @Sendable ([PermissionRequest]) -> Void
    ) async {
        let stale = StaleBit()
        let gate = LiveStoreRefreshGate()
        let lastEmitted = PermissionEmitCache()
        await refresh(
            directory: directory,
            reason: StoreRefreshReason.initialSubscribe,
            force: true,
            stale: stale,
            gate: gate,
            lastEmitted: lastEmitted,
            sessionRepository: sessionRepository,
            yield: yield
        )
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        stale.mark()
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    await refresh(
                        directory: directory,
                        reason: reason,
                        force: false,
                        stale: stale,
                        gate: gate,
                        lastEmitted: lastEmitted,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
                }
            }
            group.addTask {
                for await invalidation in invalidationBus.events() {
                    if let key = invalidation.key, key != directory { continue }
                    stale.mark()
                    await refresh(
                        directory: directory,
                        reason: invalidation.reason,
                        force: true,
                        stale: stale,
                        gate: gate,
                        lastEmitted: lastEmitted,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
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
                            try? await Task.sleep(
                                for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs)
                            )
                            guard !Task.isCancelled else { return }
                            await refresh(
                                directory: directory,
                                reason: reason,
                                force: true,
                                stale: stale,
                                gate: gate,
                                lastEmitted: lastEmitted,
                                sessionRepository: sessionRepository,
                                yield: yield
                            )
                        }
                        continue
                    }
                    guard event.type == "permission.asked"
                        || event.type == "permission.replied"
                    else { continue }
                    await refresh(
                        directory: directory,
                        reason: event.type,
                        force: true,
                        stale: stale,
                        gate: gate,
                        lastEmitted: lastEmitted,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
                }
            }
            await group.next()
            group.cancelAll()
        }
    }

    private nonisolated static func refresh(
        directory: String,
        reason: String,
        force: Bool,
        stale: StaleBit,
        gate: LiveStoreRefreshGate,
        lastEmitted: PermissionEmitCache,
        sessionRepository: any SessionRepository,
        yield: @escaping @Sendable ([PermissionRequest]) -> Void
    ) async {
        if !force, !stale.isSet {
            DiagnosticLog.info(.permission, "permission skip reason=\(reason)")
            return
        }
        let outcome = await gate.refresh(
            load: { () async -> [PermissionRequest]? in
                try? await sessionRepository.listPendingPermissions(directory: directory)
            },
            commit: { requests in
                stale.clear()
                DiagnosticLog.info(.permission, "permission refresh reason=\(reason) count=\(requests.count)")
                guard lastEmitted.shouldEmit(requests) else {
                    DiagnosticLog.info(
                        .permission,
                        "permission emit skip reason=\(reason) cause=unchanged"
                    )
                    return
                }
                yield(requests)
            }
        )
        if outcome == .failed {
            DiagnosticLog.warning(.permission, "permission refresh failed reason=\(reason)")
        }
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
