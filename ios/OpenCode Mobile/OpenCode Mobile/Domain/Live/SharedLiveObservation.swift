import Foundation

@MainActor
final class SharedLiveObservation<Value: Sendable> {
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
            continuations[id] = continuation
            if let lastValue {
                continuation.yield(lastValue)
            }
            if continuations.count == 1 {
                startUpstreamIfNeeded()
            }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.removeContinuation(id: id)
                }
            }
        }
    }

    private func removeContinuation(id: UUID) {
        continuations.removeValue(forKey: id)
        if continuations.isEmpty {
            upstreamTask?.cancel()
            upstreamTask = nil
        }
    }

    private func startUpstreamIfNeeded() {
        upstreamTask?.cancel()
        upstreamTask = makeUpstream { [weak self] value in
            self?.publish(value)
        }
    }

    private func publish(_ value: Value) {
        lastValue = value
        for continuation in continuations.values {
            continuation.yield(value)
        }
    }
}
