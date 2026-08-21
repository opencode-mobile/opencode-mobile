import Foundation

nonisolated enum WorldSignal: String, Sendable, Equatable {
    case foreground = "Foreground"
    case background = "Background"
    case backgroundStop = "BackgroundStop"
    case pathReady = "PathReady"
    case pathLost = "PathLost"
    case profileSwitched = "ProfileSwitched"
}

nonisolated protocol WorldSignals: AnyObject, Sendable {
    func signals() -> AsyncStream<WorldSignal>
    func register()
}

extension WorldSignals {
    nonisolated func register() {}
}

nonisolated final class SilentWorldSignals: WorldSignals, @unchecked Sendable {
    static let shared = SilentWorldSignals()

    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]

    func signals() -> AsyncStream<WorldSignal> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }
}

nonisolated final class FakeWorldSignals: WorldSignals, @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<WorldSignal>.Continuation] = [:]
    private var buffer: [WorldSignal] = []
    private let bufferLimit = 64

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

    func emit(_ signal: WorldSignal) {
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

extension WorldSignal {
    nonisolated var marksStale: Bool {
        self == .background || self == .pathLost
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
}
