import Foundation

nonisolated struct StoreInvalidate: Sendable, Equatable {
    let key: String?
    let reason: String
}

nonisolated final class StoreInvalidationBus: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<StoreInvalidate>.Continuation] = [:]

    func events() -> AsyncStream<StoreInvalidate> {
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

    func invalidate(key: String?, reason: String) {
        let event = StoreInvalidate(key: key, reason: reason)
        lock.lock()
        let subscribers = Array(continuations.values)
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(event)
        }
    }
}

nonisolated final class StaleBit: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func mark() {
        lock.lock()
        value = true
        lock.unlock()
    }

    func clear() {
        lock.lock()
        value = false
        lock.unlock()
    }
}
