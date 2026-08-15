import Foundation

/// Nonisolated broadcaster: byte read, parse, and event yield stay off MainActor.
/// LiveStores filter before hopping back for UI-owned work (OPE-140 Stage 2).
nonisolated final class DefaultForegroundEventStream: ForegroundEventStream, @unchecked Sendable {
    private let lock = NSLock()
    private var _connectionState: EventStreamConnectionState = .idle

    var connectionState: EventStreamConnectionState {
        lock.lock()
        defer { lock.unlock() }
        return _connectionState
    }

    private let api: EventsAPIClient
    private let adapter: EventDomainAdapter
    private var streamTask: Task<Void, Never>?
    /// Invalidates in-flight cancel/error handlers after `stop()` or a newer `start()`.
    private var streamGeneration: UInt64 = 0
    private var wasConnected = false
    private var currentStreamWasConnected = false
    private var emittedReconnectForCurrentStream = false
    private var subscriberContinuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]
    private var connectionStateContinuations: [UUID: AsyncStream<EventStreamConnectionState>.Continuation] = [:]

    init(api: EventsAPIClient, adapter: EventDomainAdapter) {
        self.api = api
        self.adapter = adapter
    }

    func events() -> AsyncStream<OpencodeEvent> {
        let subscriberID = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            self.subscriberContinuations[subscriberID] = continuation
            self.lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.subscriberContinuations.removeValue(forKey: subscriberID)
                self.lock.unlock()
            }
        }
    }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let subscriberID = UUID()
        return AsyncStream { continuation in
            self.lock.lock()
            let current = self._connectionState
            self.connectionStateContinuations[subscriberID] = continuation
            self.lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.connectionStateContinuations.removeValue(forKey: subscriberID)
                self.lock.unlock()
            }
        }
    }

    func start() {
        lock.lock()
        if streamTask != nil {
            lock.unlock()
            DiagnosticLog.debug(.sse, "foreground SSE start ignored; already running")
            return
        }
        DiagnosticLog.info(.sse, "foreground SSE stream starting")
        streamGeneration += 1
        let generation = streamGeneration
        lock.unlock()

        _ = ConnectionEpoch.shared.bump()
        let api = api
        let adapter = adapter
        // Byte read + line parse + event publish must not occupy MainActor.
        lock.lock()
        streamTask = Task(priority: .userInitiated) { [weak self] in
            await Self.runStreamLoop(api: api, adapter: adapter, owner: self, generation: generation)
        }
        lock.unlock()
    }

    func stop() {
        lock.lock()
        let hadTask = streamTask != nil
        let state = _connectionState
        streamGeneration += 1
        streamTask?.cancel()
        streamTask = nil
        wasConnected = false
        currentStreamWasConnected = false
        emittedReconnectForCurrentStream = false
        lock.unlock()
        if hadTask || state != .idle {
            DiagnosticLog.info(.sse, "foreground SSE stream stopping")
        }
        applyConnectionState(.idle)
    }

    private func publish(_ event: OpencodeEvent) {
        lock.lock()
        let conts = Array(subscriberContinuations.values)
        lock.unlock()
        for continuation in conts {
            continuation.yield(event)
        }
    }

    private func applyConnectionState(
        _ state: EventStreamConnectionState,
        generation: UInt64? = nil
    ) {
        lock.lock()
        if let generation, generation != streamGeneration {
            lock.unlock()
            return
        }
        let previous = _connectionState
        _connectionState = state
        let conts = Array(connectionStateContinuations.values)
        lock.unlock()
        if previous != state {
            DiagnosticLog.debug(.sse, "foreground SSE state \(previous) -> \(state)")
        }
        for continuation in conts {
            continuation.yield(state)
        }
    }

    private func markStreamConnected() {
        lock.lock()
        let shouldPublishReconnect =
            wasConnected && !currentStreamWasConnected && !emittedReconnectForCurrentStream
        let needsConnectedState = _connectionState != .connected
        currentStreamWasConnected = true
        wasConnected = true
        if shouldPublishReconnect {
            emittedReconnectForCurrentStream = true
        }
        lock.unlock()

        if needsConnectedState {
            applyConnectionState(.connected)
        }
        if shouldPublishReconnect {
            DiagnosticLog.info(.sse, "foreground SSE stream reconnected")
            _ = ConnectionEpoch.shared.bump()
            publish(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
        }
    }

    @discardableResult
    private func handleStreamEvent(_ event: OpencodeEvent) -> Bool {
        switch event.type {
        case "server.heartbeat":
            break
        case "message.part.delta", "message.part.updated", "message.updated", "message.removed", "message.part.removed":
            DiagnosticLog.info(.sse, "SSE event type=\(event.type) session=\(event.sessionID ?? "-")")
        default:
            DiagnosticLog.debug(.sse, "SSE event type=\(event.type) session=\(event.sessionID ?? "-")")
        }
        switch event.type {
        case "server.connected":
            markStreamConnected()
            publish(event)
            return true
        case "server.heartbeat":
            return false
        default:
            markStreamConnected()
            publish(event)
            return false
        }
    }

    private static func runStreamLoop(
        api: EventsAPIClient,
        adapter: EventDomainAdapter,
        owner: DefaultForegroundEventStream?,
        generation: UInt64
    ) async {
        final class RetryBox: @unchecked Sendable {
            var attempt = 0
        }
        let retry = RetryBox()
        while !Task.isCancelled {
            guard let owner else { return }
            let wasConnected = owner.beginConnectAttempt(generation: generation)

            do {
                try await api.streamGlobalEvents(
                    onHTTPResponse: { statusCode in
                        DiagnosticLog.info(.sse, "GET \(Self.eventPath(for: api)) opened status=\(statusCode) protocol=\(api.eventProtocolWireValue)")
                        owner.markStreamConnected()
                        retry.attempt = 0
                    },
                    onDataLine: { dataLine in
                        let parsed = adapter.parseEvents(dataLine: dataLine)
                        if parsed.isEmpty {
                            DiagnosticLog.warning(.sse, "SSE event parse failed")
                            return
                        }
                        for event in parsed {
                            if owner.handleStreamEvent(event) {
                                retry.attempt = 0
                            }
                        }
                    }
                )
                // Stream ended while still occupied — keep chrome on connecting /
                // reconnecting through the retry sleep (OPE-165). Do not publish
                // offline (Disconnected) while a foreground retry is in flight.
                owner.applyConnectionState(
                    wasConnected ? .reconnecting : .connecting,
                    generation: generation
                )
            } catch is CancellationError {
                owner.clearStreamTaskIfCurrent(generation: generation)
                owner.applyConnectionState(.idle, generation: generation)
                return
            } catch {
                DiagnosticLog.warning(.sse, "foreground SSE stream failed error=\(String(describing: error))")
                owner.applyConnectionState(
                    wasConnected ? .reconnecting : .connecting,
                    generation: generation
                )
            }

            retry.attempt += 1
            let backoffMs = min(10_000, 1_000 * retry.attempt)
            DiagnosticLog.debug(.sse, "foreground SSE retry in \(backoffMs)ms")
            try? await Task.sleep(for: .milliseconds(backoffMs))
        }
        owner?.clearStreamTaskIfCurrent(generation: generation)
    }

    private static func eventPath(for api: EventsAPIClient) -> String {
        "/\(api.eventSubscribePath)"
    }

    private func beginConnectAttempt(generation: UInt64) -> Bool {
        lock.lock()
        currentStreamWasConnected = false
        emittedReconnectForCurrentStream = false
        let previouslyConnected = wasConnected
        lock.unlock()
        applyConnectionState(
            previouslyConnected ? .reconnecting : .connecting,
            generation: generation
        )
        return previouslyConnected
    }

    private func clearStreamTaskIfCurrent(generation: UInt64) {
        lock.lock()
        if generation == streamGeneration {
            streamTask = nil
        }
        lock.unlock()
    }
}
