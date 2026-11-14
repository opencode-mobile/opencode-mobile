import Foundation

public nonisolated final class LiveConnectionRevisionSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Void>.Continuation] = [:]

    public func stream() -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self)
        lock.lock()
        continuations[id] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.continuations.removeValue(forKey: id)
            self.lock.unlock()
        }
        return stream
    }

    public func yield() {
        lock.lock()
        let conts = Array(continuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(())
        }
    }

    public init() {}
}
