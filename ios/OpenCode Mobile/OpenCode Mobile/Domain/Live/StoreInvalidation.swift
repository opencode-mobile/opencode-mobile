import Foundation

nonisolated func awaitWithTimeout(
    milliseconds: Int,
    operation: @escaping @Sendable () async -> Void
) async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        let waiter = TimeoutOnceResume(continuation)
        let op = Task {
            await operation()
            waiter.finish()
        }
        let timeout = Task {
            try? await Task.sleep(for: .milliseconds(max(0, milliseconds)))
            waiter.finish()
            op.cancel()
        }
        waiter.onFinished {
            timeout.cancel()
        }
    }
}

private final class TimeoutOnceResume: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var finished = false
    private var onFinishedHandler: (@Sendable () -> Void)?

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func onFinished(_ handler: @escaping @Sendable () -> Void) {
        lock.lock()
        if finished {
            lock.unlock()
            handler()
            return
        }
        onFinishedHandler = handler
        lock.unlock()
    }

    func finish() {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        let pending = continuation
        continuation = nil
        let handler = onFinishedHandler
        onFinishedHandler = nil
        lock.unlock()
        handler?()
        pending?.resume()
    }
}

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

    func invalidateAndWait(key: String?, reason: String) async {
        lock.lock()
        let hasSubscribers = !continuations.isEmpty
        lock.unlock()
        if !hasSubscribers { return }

        let waiter = AckWaiter()
        let timeoutMs = ackTimeoutMs
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
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
