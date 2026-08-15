import Foundation
@testable import OpenCode_Mobile

final class FakeDeviceNetworkPath: DeviceNetworkPath, @unchecked Sendable {
    private let lock = NSLock()
    private var _status: DeviceNetworkPathStatus
    private var continuations: [UUID: AsyncStream<DeviceNetworkPathStatus>.Continuation] = [:]
    private var readyWaiters: [CheckedContinuation<Void, Never>] = []

    var status: DeviceNetworkPathStatus {
        lock.lock()
        defer { lock.unlock() }
        return _status
    }

    init(status: DeviceNetworkPathStatus = .ready) {
        _status = status
    }

    func set(_ status: DeviceNetworkPathStatus) {
        lock.lock()
        _status = status
        let continuations = Array(self.continuations.values)
        let waiters = status == .ready ? readyWaiters : []
        if status == .ready {
            readyWaiters.removeAll()
        }
        lock.unlock()
        for continuation in continuations {
            continuation.yield(status)
        }
        for waiter in waiters {
            waiter.resume()
        }
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
}
