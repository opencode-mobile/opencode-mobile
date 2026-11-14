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
    func emitProfileSwitched()
}

extension WorldSignals {
    nonisolated func register() {}
    nonisolated func emitProfileSwitched() {}
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

extension WorldSignal {
    nonisolated var marksStale: Bool {
        self == .backgroundStop || self == .pathLost || self == .profileSwitched
    }
}

nonisolated enum StoreRefreshReason {
    static let pullToRefresh = "pull-to-refresh"
    static let initialSubscribe = "initial-subscribe"
}
