import Foundation

nonisolated final class DefaultLiveUpdates: LiveUpdates, @unchecked Sendable {
    private let worldSignals: any WorldSignals
    private let stream: any ForegroundEventStream
    private let path: any DeviceNetworkPath
    private let hasActiveConnection: @Sendable () -> Bool
    private let lock = NSLock()
    private var _chrome: LiveUpdatesChrome
    private var continuations: [UUID: AsyncStream<LiveUpdatesChrome>.Continuation] = [:]
    private var observeTask: Task<Void, Never>?

    init(
        worldSignals: any WorldSignals,
        stream: any ForegroundEventStream,
        path: any DeviceNetworkPath,
        hasActiveConnection: @escaping @Sendable () -> Bool
    ) {
        self.worldSignals = worldSignals
        self.stream = stream
        self.path = path
        self.hasActiveConnection = hasActiveConnection
        _chrome = composeLiveUpdates(
            connectionState: stream.connectionState,
            path: path.status,
            occupancy: worldSignals.occupancy,
            hasConnection: hasActiveConnection()
        )
        observeTask = Task { [weak self] in
            await self?.observe()
        }
    }

    var chrome: LiveUpdatesChrome {
        lock.lock()
        defer { lock.unlock() }
        return _chrome
    }

    func updates() -> AsyncStream<LiveUpdatesChrome> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            let current = _chrome
            lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    private func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { [weak self] in
                guard let self else { return }
                for await _ in self.worldSignals.occupancyUpdates() {
                    self.publish()
                }
            }
            group.addTask { [weak self] in
                guard let self else { return }
                for await _ in self.stream.connectionStateUpdates() {
                    self.publish()
                }
            }
            group.addTask { [weak self] in
                guard let self else { return }
                for await _ in self.path.updates() {
                    self.publish()
                }
            }
            group.addTask { [weak self] in
                guard let self else { return }
                for await signal in self.worldSignals.signals() {
                    if signal == .profileSwitched || signal == .foreground {
                        self.publish()
                    }
                }
            }
        }
    }

    private func publish() {
        let next = composeLiveUpdates(
            connectionState: stream.connectionState,
            path: path.status,
            occupancy: worldSignals.occupancy,
            hasConnection: hasActiveConnection()
        )
        lock.lock()
        let changed = _chrome != next
        _chrome = next
        let subscribers = Array(continuations.values)
        lock.unlock()
        guard changed else { return }
        for continuation in subscribers {
            continuation.yield(next)
        }
    }
}
