import Foundation

/// Process-wide bridge between the T3 socket owner (`T3EnvironmentClient`) and
/// the foreground live-connection owner (`DefaultForegroundEventStream`).
///
/// The client publishes real socket state here so the connection indicator
/// reflects the websocket, not a hard-coded "connected". The event stream
/// forwards its lifecycle (`start` on foreground / profile rebind, `stop` on
/// background / profile switch) so the client can reset its socket and caches
/// without the DI container wiring the two together.
nonisolated final class T3LiveSignals: @unchecked Sendable {
    static let shared = T3LiveSignals()

    enum Lifecycle: Sendable {
        case start
        case stop
    }

    private let lock = NSLock()
    private var _connectionState: EventStreamConnectionState = .disconnected
    private var stateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]
    private var lifecycleHandlers: [UUID: @Sendable (Lifecycle) async -> Void] = [:]
    private var eventContinuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]

    var connectionState: EventStreamConnectionState {
        lock.lock()
        defer { lock.unlock() }
        return _connectionState
    }

    func publish(_ state: EventStreamConnectionState) {
        lock.lock()
        let changed = _connectionState != state
        _connectionState = state
        let conts = Array(stateContinuations.values)
        lock.unlock()
        guard changed else { return }
        for continuation in conts {
            continuation.yield(state)
        }
    }

    /// Yields the current state first, then every change.
    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let id = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._connectionState
            self.stateContinuations[id] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.stateContinuations.removeValue(forKey: id)
                self.lock.unlock()
            }
        }
    }

    /// Synthetic opencode-shaped events derived from the T3 shell (thread
    /// lifecycle, status, approvals). `DefaultForegroundEventStream` forwards
    /// them to its subscribers while a T3 profile is live.
    func publishEvent(_ event: OpencodeEvent) {
        lock.lock()
        let conts = Array(eventContinuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(event)
        }
    }

    var hasEventSubscribers: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !eventContinuations.isEmpty
    }

    func eventUpdates() -> AsyncStream<OpencodeEvent> {
        let id = UUID()
        let (stream, continuation) = AsyncStream.makeStream(of: OpencodeEvent.self)
        lock.lock()
        eventContinuations[id] = continuation
        lock.unlock()
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.eventContinuations.removeValue(forKey: id)
            self.lock.unlock()
        }
        return stream
    }

    @discardableResult
    func onLifecycle(_ handler: @escaping @Sendable (Lifecycle) async -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        lifecycleHandlers[id] = handler
        lock.unlock()
        return id
    }

    func removeLifecycleHandler(_ id: UUID) {
        lock.lock()
        lifecycleHandlers.removeValue(forKey: id)
        lock.unlock()
    }

    /// Awaits every handler so `stop` completes only after the socket is closed.
    func emit(_ lifecycle: Lifecycle) async {
        lock.lock()
        let handlers = Array(lifecycleHandlers.values)
        lock.unlock()
        for handler in handlers {
            await handler(lifecycle)
        }
    }
}
