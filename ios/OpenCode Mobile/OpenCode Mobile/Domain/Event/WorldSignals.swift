import Foundation

nonisolated enum WorldSignal: String, Sendable, Equatable {
    case foreground = "Foreground"
    case background = "Background"
    case backgroundStop = "BackgroundStop"
    case pathReady = "PathReady"
    case pathLost = "PathLost"
    case profileSwitched = "ProfileSwitched"
}

nonisolated enum StreamOccupancy: String, Sendable, Equatable {
    case active
    case grace
    case stopped
}

nonisolated protocol WorldSignals: AnyObject, Sendable {
    var occupancy: StreamOccupancy { get }
    func occupancyUpdates() -> AsyncStream<StreamOccupancy>
    func signals() -> AsyncStream<WorldSignal>
    func register()
}

extension WorldSignals {
    nonisolated func register() {}
}

nonisolated final class SilentWorldSignals: WorldSignals, @unchecked Sendable {
    static let shared = SilentWorldSignals()

    private let lock = NSLock()
    private var signalContinuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]
    private var occupancyContinuations: [UUID: AsyncStream<StreamOccupancy>.Continuation] = [:]

    var occupancy: StreamOccupancy { .active }

    func occupancyUpdates() -> AsyncStream<StreamOccupancy> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            occupancyContinuations[id] = continuation
            lock.unlock()
            continuation.yield(.active)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.occupancyContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    func signals() -> AsyncStream<WorldSignal> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            signalContinuations[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.signalContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }
}

nonisolated final class FakeWorldSignals: WorldSignals, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]
    private var occupancyContinuations: [UUID: AsyncStream<StreamOccupancy>.Continuation] = [:]
    private var buffer: [WorldSignal] = []
    private let bufferLimit = 64
    private var _occupancy: StreamOccupancy = .active

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

    func setOccupancy(_ occupancy: StreamOccupancy) {
        lock.lock()
        _occupancy = occupancy
        let occupancySubscribers = Array(occupancyContinuations.values)
        lock.unlock()
        for continuation in occupancySubscribers {
            continuation.yield(occupancy)
        }
    }

    func emit(_ signal: WorldSignal) {
        lock.lock()
        switch signal {
        case .foreground:
            _occupancy = .active
        case .background:
            _occupancy = .grace
        case .backgroundStop:
            _occupancy = .stopped
        case .pathReady, .pathLost, .profileSwitched:
            break
        }
        let occupancy = _occupancy
        let occupancySubscribers = Array(occupancyContinuations.values)
        let subscribers = Array(continuations.values)
        if subscribers.isEmpty {
            buffer.append(signal)
            if buffer.count > bufferLimit {
                buffer.removeFirst()
            }
        }
        lock.unlock()
        for continuation in occupancySubscribers {
            continuation.yield(occupancy)
        }
        for continuation in subscribers {
            continuation.yield(signal)
        }
    }
}

extension WorldSignal {
    nonisolated var marksStale: Bool {
        self == .backgroundStop || self == .pathLost || self == .profileSwitched
    }

    nonisolated var catchUpReason: String? {
        switch self {
        case .foreground:
            StoreRefreshReason.backgroundReturn
        case .pathReady:
            StoreRefreshReason.pathReady
        default:
            nil
        }
    }
}

nonisolated enum StoreRefreshReason {
    static let backgroundReturn = "background-return"
    static let pathReady = "path-ready"
    static let pullToRefresh = "pull-to-refresh"
    static let initialSubscribe = "initial-subscribe"
    static let streamReconnected = "stream.reconnected"
    static let serverConnected = "server.connected"
    static let connectionSnapshot = "connection-snapshot"
}
