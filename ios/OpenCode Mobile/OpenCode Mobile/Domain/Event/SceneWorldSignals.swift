import Foundation

/// Scene-level world adapter: lifecycle, path, and profile edges.
/// Starts and stops `ForegroundEventStream`. Does not call feature REST.
nonisolated final class SceneWorldSignals: WorldSignals, @unchecked Sendable {
    nonisolated static let stopGraceMs = 5_000

    private let stream: any ForegroundEventStream
    private let deviceNetworkPath: any DeviceNetworkPath
    private let hasActiveConnection: @Sendable () -> Bool
    private let connectionSessionCoordinator: ConnectionSessionCoordinator?
    private let stopGrace: Duration
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]
    private var buffer: [WorldSignal] = []
    private let bufferLimit = 32
    private var stopTask: Task<Void, Never>?
    private var registered = false

    init(
        stream: any ForegroundEventStream,
        deviceNetworkPath: any DeviceNetworkPath,
        hasActiveConnection: @escaping @Sendable () -> Bool,
        connectionSessionCoordinator: ConnectionSessionCoordinator? = nil,
        stopGrace: Duration = .milliseconds(stopGraceMs)
    ) {
        self.stream = stream
        self.deviceNetworkPath = deviceNetworkPath
        self.hasActiveConnection = hasActiveConnection
        self.connectionSessionCoordinator = connectionSessionCoordinator
        self.stopGrace = stopGrace
    }

    func register() {
        lock.lock()
        if registered {
            lock.unlock()
            return
        }
        registered = true
        lock.unlock()
        startCollectors()
    }

    func startCollectors() {
        let path = deviceNetworkPath
        Task { [weak self] in
            var isFirst = true
            for await status in path.updates() {
                guard let self else { return }
                if isFirst {
                    isFirst = false
                    continue
                }
                switch status {
                case .ready:
                    self.emit(.pathReady)
                case .unavailable:
                    self.emit(.pathLost)
                }
            }
        }
        guard let coordinator = connectionSessionCoordinator else { return }
        Task { @MainActor [weak self] in
            coordinator.onProfileSaved {
                self?.emit(.profileSwitched)
            }
            coordinator.onProfileCleared {
                self?.emit(.profileSwitched)
            }
        }
    }

    func onForeground() {
        lock.lock()
        stopTask?.cancel()
        stopTask = nil
        lock.unlock()
        emit(.foreground)
        if hasActiveConnection() {
            stream.start()
        }
    }

    func onBackground() {
        lock.lock()
        stopTask?.cancel()
        let grace = stopGrace
        stopTask = Task { [weak self] in
            try? await Task.sleep(for: grace)
            guard !Task.isCancelled else { return }
            self?.emit(.backgroundStop)
            self?.stream.stop()
        }
        lock.unlock()
        emit(.background)
    }

    func signals() -> AsyncStream<WorldSignal> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            let pending = buffer
            buffer.removeAll()
            lock.unlock()
            for signal in pending {
                continuation.yield(signal)
            }
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    private func emit(_ signal: WorldSignal) {
        DiagnosticLog.info(.sse, "world signal=\(signal.rawValue)")
        lock.lock()
        let subscribers = Array(continuations.values)
        if subscribers.isEmpty {
            buffer.append(signal)
            if buffer.count > bufferLimit {
                buffer.removeFirst()
            }
        }
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(signal)
        }
    }
}
