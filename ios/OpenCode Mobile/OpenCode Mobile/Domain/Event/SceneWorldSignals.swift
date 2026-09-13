import Foundation

/// Scene-level world adapter: lifecycle, path, and profile edges.
/// Starts and stops `ForegroundEventStream`. Does not call feature REST.
nonisolated final class SceneWorldSignals: WorldSignals, @unchecked Sendable {
    nonisolated static let stopGraceMs = 5_000
    nonisolated static let inactiveGraceMs = 2_000

    private let stream: any ForegroundEventStream
    private let deviceNetworkPath: any DeviceNetworkPath
    private let hasActiveConnection: @Sendable () -> Bool
    private let activeConnectionManager: ActiveConnectionManager?
    private let stopGrace: Duration
    private let inactiveGrace: Duration
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]
    private var buffer: [WorldSignal] = []
    private let bufferLimit = 32
    private var stopTask: Task<Void, Never>?
    private var inactiveTask: Task<Void, Never>?
    private var registered = false
    private var _occupancy: StreamOccupancy = .active
    private var occupancyContinuations: [UUID: AsyncStream<StreamOccupancy>.Continuation] = [:]

    init(
        stream: any ForegroundEventStream,
        deviceNetworkPath: any DeviceNetworkPath,
        hasActiveConnection: @escaping @Sendable () -> Bool,
        activeConnectionManager: ActiveConnectionManager? = nil,
        stopGrace: Duration = .milliseconds(stopGraceMs),
        inactiveGrace: Duration = .milliseconds(inactiveGraceMs)
    ) {
        self.stream = stream
        self.deviceNetworkPath = deviceNetworkPath
        self.hasActiveConnection = hasActiveConnection
        self.activeConnectionManager = activeConnectionManager
        self.stopGrace = stopGrace
        self.inactiveGrace = inactiveGrace
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
                    self.wakeReconnect()
                    self.emit(.pathReady)
                case .unavailable:
                    self.emit(.pathLost)
                }
            }
        }
    }

    func onForeground() {
        lock.lock()
        inactiveTask?.cancel()
        inactiveTask = nil
        stopTask?.cancel()
        stopTask = nil
        _occupancy = .active
        let occupancySubscribers = Array(occupancyContinuations.values)
        lock.unlock()
        for continuation in occupancySubscribers {
            continuation.yield(.active)
        }
        wakeReconnect()
        emit(.foreground)
        startIfConnected(reason: "foreground")
    }

    func onInactive() {
        lock.lock()
        inactiveTask?.cancel()
        let grace = inactiveGrace
        inactiveTask = Task { [weak self] in
            try? await Task.sleep(for: grace)
            guard !Task.isCancelled else { return }
            self?.onBackground()
        }
        lock.unlock()
    }

    private func startIfConnected(reason: String) {
        if occupancy == .stopped { return }
        if hasActiveConnection() {
            stream.start()
        } else {
            DiagnosticLog.info(.sse, "\(reason) SSE start skipped; no active connection")
        }
    }

    func onBackground() {
        lock.lock()
        inactiveTask?.cancel()
        inactiveTask = nil
        stopTask?.cancel()
        _occupancy = .grace
        let occupancySubscribers = Array(occupancyContinuations.values)
        let grace = stopGrace
        stopTask = Task { [weak self] in
            try? await Task.sleep(for: grace)
            guard !Task.isCancelled else { return }
            self?.setOccupancy(.stopped)
            self?.emit(.backgroundStop)
            await self?.stream.stop()
        }
        lock.unlock()
        for continuation in occupancySubscribers {
            continuation.yield(.grace)
        }
        emit(.background)
    }

    func onBackgroundStop() async {
        lock.lock()
        inactiveTask?.cancel()
        inactiveTask = nil
        stopTask?.cancel()
        stopTask = nil
        lock.unlock()
        setOccupancy(.stopped)
        emit(.backgroundStop)
        await stream.stop()
    }

    func onPathReady() {
        wakeReconnect()
        emit(.pathReady)
    }

    func onPathLost() {
        emit(.pathLost)
    }

    func onProfileSwitched() {
        emitProfileSwitched()
    }

    func onProfileCleared() {
        emitProfileSwitched()
    }

    func emitProfileSwitched() {
        emit(.profileSwitched)
    }

    var occupancy: StreamOccupancy {
        lock.lock()
        defer { lock.unlock() }
        return _occupancy
    }

    func occupancyUpdates() -> AsyncStream<StreamOccupancy> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            occupancyContinuations[id] = continuation
            let current = _occupancy
            lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.occupancyContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    private func wakeReconnect() {
        activeConnectionManager?.resetProbeFailure()
        stream.onReconnectWake()
    }

    private func setOccupancy(_ occupancy: StreamOccupancy) {
        lock.lock()
        _occupancy = occupancy
        let subscribers = Array(occupancyContinuations.values)
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(occupancy)
        }
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
