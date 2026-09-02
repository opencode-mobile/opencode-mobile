import Foundation

nonisolated struct StoreInvalidate: Sendable, Equatable {
    let key: String?
    let reason: String
    let ack: (@Sendable () -> Void)?

    init(key: String?, reason: String, ack: (@Sendable () -> Void)? = nil) {
        self.key = key
        self.reason = reason
        self.ack = ack
    }

    static func == (lhs: StoreInvalidate, rhs: StoreInvalidate) -> Bool {
        lhs.key == rhs.key && lhs.reason == rhs.reason
    }
}

nonisolated final class StoreInvalidationBus: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<StoreInvalidate>.Continuation] = [:]
    private let ackTimeoutMs: Int

    /// - Parameter ackTimeoutMs: ceiling for ``invalidateAndWait(key:reason:)``.
    ///   Injectable so tests can bound the wait without waiting on the production
    ///   ceiling.
    init(ackTimeoutMs: Int = LiveStoreRefreshPolicy.invalidationAckTimeoutMs) {
        self.ackTimeoutMs = ackTimeoutMs
    }

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
        yield(StoreInvalidate(key: key, reason: reason))
    }

    /// Emits an invalidation and waits for a collector to acknowledge it.
    ///
    /// The zero-subscriber check is only a fast path: a subscriber can disappear
    /// between the check and the yield, and a collector can be torn down after
    /// receiving the event, so the wait is bounded by `ackTimeoutMs` and also ends
    /// when the calling task is cancelled. Returning means "stop waiting", never
    /// "the store now holds fresh data" — callers must not read it as a freshness
    /// guarantee.
    func invalidateAndWait(key: String?, reason: String) async {
        lock.lock()
        let hasSubscribers = !continuations.isEmpty
        lock.unlock()
        if !hasSubscribers { return }

        let waiter = AckWaiter()
        let timeoutMs = ackTimeoutMs
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                // Attach before yielding: a collector can ack synchronously.
                waiter.attach(OnceResume(continuation))
                waiter.setTimeoutTask(Task {
                    try? await Task.sleep(for: .milliseconds(timeoutMs))
                    waiter.finish()
                })
                yield(StoreInvalidate(key: key, reason: reason, ack: { waiter.finish() }))
            }
        } onCancel: {
            waiter.finish()
        }
    }

    private func yield(_ event: StoreInvalidate) {
        lock.lock()
        let subscribers = Array(continuations.values)
        lock.unlock()
        for continuation in subscribers {
            continuation.yield(event)
        }
    }
}

/// Bounds one ack wait: resumes exactly once, whichever of ack, timeout, or task
/// cancellation happens first. Cancellation can win before the continuation exists,
/// so a `finish()` that arrives early is replayed on `attach`.
private final class AckWaiter: @unchecked Sendable {
    private let lock = NSLock()
    private var resume: OnceResume?
    private var timeoutTask: Task<Void, Never>?
    private var finished = false

    func attach(_ value: OnceResume) {
        lock.lock()
        if finished {
            lock.unlock()
            value.go()
            return
        }
        resume = value
        lock.unlock()
    }

    func setTimeoutTask(_ task: Task<Void, Never>) {
        lock.lock()
        if finished {
            lock.unlock()
            task.cancel()
            return
        }
        timeoutTask = task
        lock.unlock()
    }

    func finish() {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        let pending = resume
        let task = timeoutTask
        resume = nil
        timeoutTask = nil
        lock.unlock()
        task?.cancel()
        pending?.go()
    }
}

private final class OnceResume: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func go() {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume()
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

nonisolated final class ActiveDirectorySet: @unchecked Sendable {
    private let lock = NSLock()
    private var values: Set<String> = []

    func insert(_ value: String) {
        lock.lock()
        values.insert(value)
        lock.unlock()
    }

    func remove(_ value: String) {
        lock.lock()
        values.remove(value)
        lock.unlock()
    }

    func contains(_ value: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return values.contains(value)
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return Array(values)
    }
}
