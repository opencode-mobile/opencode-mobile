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
