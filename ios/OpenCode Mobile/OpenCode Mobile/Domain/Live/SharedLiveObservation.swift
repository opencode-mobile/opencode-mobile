import Foundation

nonisolated final class SharedLiveObservation<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Value>.Continuation] = [:]
    private var upstreamTask: Task<Void, Never>?
    private var stopTask: Task<Void, Never>?
    private var lastValue: Value?
    private var upstreamGeneration: UInt64 = 0
    private var isFinished = false
    private let makeUpstream: (@escaping @Sendable (Value) -> Void) -> Task<Void, Never>

    init(makeUpstream: @escaping (@escaping @Sendable (Value) -> Void) -> Task<Void, Never>) {
        self.makeUpstream = makeUpstream
    }

    deinit {
        finish()
    }

    var isTerminated: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isFinished
    }

    func finish() {
        lock.lock()
        if isFinished {
            lock.unlock()
            return
        }
        isFinished = true
        stopTask?.cancel()
        stopTask = nil
        let task = upstreamTask
        upstreamTask = nil
        upstreamGeneration &+= 1
        let conts = Array(continuations.values)
        continuations.removeAll()
        lastValue = nil
        lock.unlock()
        task?.cancel()
        for continuation in conts {
            continuation.finish()
        }
    }

    func stream() -> AsyncStream<Value> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            if isFinished {
                lock.unlock()
                continuation.finish()
                return
            }
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
        let shouldScheduleStop = continuations.isEmpty && !isFinished
        lock.unlock()
        if shouldScheduleStop {
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
        upstreamGeneration &+= 1
        stopTask = nil
        lock.unlock()
        task?.cancel()
    }

    private func startUpstreamIfNeeded() {
        lock.lock()
        stopTask?.cancel()
        stopTask = nil
        guard !isFinished, upstreamTask == nil else {
            lock.unlock()
            return
        }
        upstreamGeneration &+= 1
        let generation = upstreamGeneration
        let upstream = makeUpstream { [weak self] value in
            self?.publish(value)
        }
        upstreamTask = Task { [weak self] in
            await withTaskCancellationHandler {
                await upstream.value
            } onCancel: {
                upstream.cancel()
            }
            self?.clearUpstream(generation: generation)
        }
        lock.unlock()
    }

    private func clearUpstream(generation: UInt64) {
        lock.lock()
        if upstreamGeneration == generation {
            upstreamTask = nil
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

func resubscribingLiveStream<Value: Sendable>(
    _ make: @escaping @MainActor @Sendable () -> AsyncStream<Value>
) -> AsyncStream<Value> {
    AsyncStream { continuation in
        let task = Task { @MainActor in
            while !Task.isCancelled {
                for await value in make() {
                    continuation.yield(value)
                }
                if Task.isCancelled { break }
                await Task.yield()
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}
