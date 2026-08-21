import Foundation

nonisolated final class SharedLiveObservation<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Value>.Continuation] = [:]
    private var upstreamTask: Task<Void, Never>?
    private var stopTask: Task<Void, Never>?
    private var lastValue: Value?
    private let makeUpstream: (@escaping @Sendable (Value) -> Void) -> Task<Void, Never>

    init(makeUpstream: @escaping (@escaping @Sendable (Value) -> Void) -> Task<Void, Never>) {
        self.makeUpstream = makeUpstream
    }

    deinit {
        stopTask?.cancel()
        upstreamTask?.cancel()
    }

    func stream() -> AsyncStream<Value> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            if let lastValue {
                continuation.yield(lastValue)
            }
            lock.unlock()
            startUpstreamIfNeeded()
            continuation.onTermination = { [weak self] _ in
                self?.removeContinuation(id: id)
            }
        }
    }

    private func removeContinuation(id: UUID) {
        lock.lock()
        continuations.removeValue(forKey: id)
        let empty = continuations.isEmpty
        lock.unlock()
        if empty {
            scheduleStop()
        }
    }

    private func scheduleStop() {
        let timeoutMs = LiveStoreRefreshPolicy.subscriberStopTimeoutMs
        lock.lock()
        stopTask?.cancel()
        stopTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(timeoutMs))
            guard !Task.isCancelled else { return }
            self?.stopUpstreamIfEmpty()
        }
        lock.unlock()
    }

    private func stopUpstreamIfEmpty() {
        lock.lock()
        guard continuations.isEmpty else {
            lock.unlock()
            return
        }
        let task = upstreamTask
        upstreamTask = nil
        stopTask = nil
        lock.unlock()
        task?.cancel()
    }

    private func startUpstreamIfNeeded() {
        lock.lock()
        stopTask?.cancel()
        stopTask = nil
        let needsStart = upstreamTask == nil
        if needsStart {
            upstreamTask = makeUpstream { [weak self] value in
                self?.publish(value)
            }
        }
        lock.unlock()
    }

    private func publish(_ value: Value) {
        lock.lock()
        lastValue = value
        let conts = Array(continuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(value)
        }
    }
}
