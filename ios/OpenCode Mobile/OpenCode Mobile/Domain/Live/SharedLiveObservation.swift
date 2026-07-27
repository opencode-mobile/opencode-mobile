import Foundation

final class SharedLiveObservation<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Value>.Continuation] = [:]
    private var upstreamTask: Task<Void, Never>?
    private var lastValue: Value?
    private let makeUpstream: (@escaping (Value) -> Void) -> Task<Void, Never>

    init(makeUpstream: @escaping (@escaping (Value) -> Void) -> Task<Void, Never>) {
        self.makeUpstream = makeUpstream
    }

    func stream() -> AsyncStream<Value> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            if let lastValue {
                continuation.yield(lastValue)
            }
            let shouldStart = continuations.count == 1
            lock.unlock()
            if shouldStart {
                startUpstreamIfNeeded()
            }
            continuation.onTermination = { [weak self] _ in
                self?.removeContinuation(id: id)
            }
        }
    }

    private func removeContinuation(id: UUID) {
        lock.lock()
        continuations.removeValue(forKey: id)
        let empty = continuations.isEmpty
        let task = upstreamTask
        if empty {
            upstreamTask = nil
        }
        lock.unlock()
        if empty {
            task?.cancel()
        }
    }

    private func startUpstreamIfNeeded() {
        lock.lock()
        upstreamTask?.cancel()
        upstreamTask = makeUpstream { [weak self] value in
            self?.publish(value)
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
