import Foundation

nonisolated enum LiveUpdatesLabel: String, Sendable, Equatable {
    case live
    case connecting
    case offline
}

nonisolated struct LiveUpdatesChrome: Sendable, Equatable {
    var label: LiveUpdatesLabel
    var canMutate: Bool
}

nonisolated protocol LiveUpdates: AnyObject, Sendable {
    var chrome: LiveUpdatesChrome { get }
    func updates() -> AsyncStream<LiveUpdatesChrome>
}

nonisolated final class FixedLiveUpdates: LiveUpdates, @unchecked Sendable {
    private let lock = NSLock()
    private var _chrome: LiveUpdatesChrome
    private var continuations: [UUID: AsyncStream<LiveUpdatesChrome>.Continuation] = [:]

    init(
        chrome: LiveUpdatesChrome = LiveUpdatesChrome(label: .live, canMutate: true)
    ) {
        _chrome = chrome
    }

    var chrome: LiveUpdatesChrome {
        lock.lock()
        defer { lock.unlock() }
        return _chrome
    }

    func set(_ chrome: LiveUpdatesChrome) {
        lock.lock()
        _chrome = chrome
        let subscribers = Array(continuations.values)
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(chrome)
        }
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
}

nonisolated func composeLiveUpdates(
    connectionState: EventStreamConnectionState,
    path: DeviceNetworkPathStatus,
    occupancy: StreamOccupancy,
    hasConnection: Bool
) -> LiveUpdatesChrome {
    let canMutate = hasConnection && path == .ready && occupancy != .stopped
    let label: LiveUpdatesLabel
    if !hasConnection {
        label = .offline
    } else if path == .unavailable {
        label = .offline
    } else if occupancy == .stopped {
        label = .offline
    } else if connectionState == .connected {
        label = .live
    } else {
        label = .connecting
    }
    return LiveUpdatesChrome(label: label, canMutate: canMutate)
}
