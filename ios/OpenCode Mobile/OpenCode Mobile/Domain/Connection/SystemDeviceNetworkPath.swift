import Foundation
import Network

final class SystemDeviceNetworkPath: DeviceNetworkPath, @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var _status: DeviceNetworkPathStatus = .ready
    private var continuations: [UUID: AsyncStream<DeviceNetworkPathStatus>.Continuation] = [:]
    private var readyWaiters: [CheckedContinuation<Void, Never>] = []

    var status: DeviceNetworkPathStatus {
        lock.lock()
        defer { lock.unlock() }
        return _status
    }

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let next: DeviceNetworkPathStatus =
                path.status == .satisfied ? .ready : .unavailable
            self?.setStatus(next)
        }
        monitor.start(queue: DispatchQueue(label: "dev.opencode.mobile.device-network-path"))
    }

    func updates() -> AsyncStream<DeviceNetworkPathStatus> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            let current = _status
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

    func waitUntilReady() async {
        let current: DeviceNetworkPathStatus =
            monitor.currentPath.status == .satisfied ? .ready : .unavailable
        if current == .ready {
            setStatus(.ready)
            return
        }
        setStatus(.unavailable)
        lock.lock()
        if _status == .ready {
            lock.unlock()
            return
        }
        await withCheckedContinuation { continuation in
            readyWaiters.append(continuation)
            lock.unlock()
        }
    }

    private func setStatus(_ status: DeviceNetworkPathStatus) {
        lock.lock()
        let changed = _status != status
        _status = status
        let continuations = changed ? Array(self.continuations.values) : []
        let waiters = status == .ready ? readyWaiters : []
        if status == .ready {
            readyWaiters.removeAll()
        }
        lock.unlock()
        guard changed else { return }
        for continuation in continuations {
            continuation.yield(status)
        }
        for waiter in waiters {
            waiter.resume()
        }
    }
}
