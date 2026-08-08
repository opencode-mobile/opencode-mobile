import Foundation

@MainActor
final class DefaultForegroundEventStream: ForegroundEventStream {
    private(set) var connectionState: EventStreamConnectionState = .idle

    private let api: EventsAPIClient
    private let adapter: EventDomainAdapter
    private var streamTask: Task<Void, Never>?
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
            subscriberContinuations[subscriberID] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.subscriberContinuations.removeValue(forKey: subscriberID)
                }
            }
        }
    }

    func connectionStateUpdates() -> AsyncStream<EventStreamConnectionState> {
        let subscriberID = UUID()
        return AsyncStream { continuation in
            continuation.yield(connectionState)
            connectionStateContinuations[subscriberID] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.connectionStateContinuations.removeValue(forKey: subscriberID)
                }
            }
        }
    }

    func start() {
        stop()
        DiagnosticLog.info(.sse, "foreground SSE stream starting")
        _ = ConnectionEpoch.shared.bump()
        let api = api
        let adapter = adapter
        // Byte read + line parse must not occupy MainActor. State publish hops
        // back via MainActor.run. Running the loop on MainActor starved UI on
        // send (production freezes; diagnostic blackout).
        streamTask = Task(priority: .userInitiated) { [weak self] in
            await Self.runStreamLoop(api: api, adapter: adapter, owner: self)
        }
    }

    func stop() {
        if streamTask != nil || connectionState != .idle {
            DiagnosticLog.info(.sse, "foreground SSE stream stopping")
        }
        streamTask?.cancel()
        streamTask = nil
        wasConnected = false
        currentStreamWasConnected = false
        emittedReconnectForCurrentStream = false
        applyConnectionState(.idle)
    }

    private func publish(_ event: OpencodeEvent) {
        for continuation in subscriberContinuations.values {
            continuation.yield(event)
        }
    }

    private func applyConnectionState(_ state: EventStreamConnectionState) {
        if connectionState != state {
            DiagnosticLog.debug(.sse, "foreground SSE state \(connectionState) -> \(state)")
        }
        connectionState = state
        for continuation in connectionStateContinuations.values {
            continuation.yield(state)
        }
    }

    private func markStreamConnected() {
        let shouldPublishReconnect = wasConnected && !currentStreamWasConnected && !emittedReconnectForCurrentStream
        if connectionState != .connected {
            applyConnectionState(.connected)
        }
        if shouldPublishReconnect {
            DiagnosticLog.info(.sse, "foreground SSE stream reconnected")
            _ = ConnectionEpoch.shared.bump()
            publish(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
            emittedReconnectForCurrentStream = true
        }
        currentStreamWasConnected = true
        wasConnected = true
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

    /// Nonisolated so the Task body does not hop to MainActor for the whole loop.
    private nonisolated static func runStreamLoop(
        api: EventsAPIClient,
        adapter: EventDomainAdapter,
        owner: DefaultForegroundEventStream?
    ) async {
        var retryAttempt = 0
        while !Task.isCancelled {
            let wasConnected = await MainActor.run { [weak owner] in
                guard let owner else { return false }
                owner.currentStreamWasConnected = false
                owner.emittedReconnectForCurrentStream = false
                let wasConnected = owner.wasConnected
                owner.applyConnectionState(wasConnected ? .reconnecting : .connecting)
                return wasConnected
            }

            do {
                try await api.streamGlobalEvents(
                    onHTTPResponse: { statusCode in
                        DiagnosticLog.info(.sse, "GET /global/event opened status=\(statusCode)")
                        await MainActor.run { [weak owner] in
                            owner?.markStreamConnected()
                        }
                        retryAttempt = 0
                    },
                    onDataLine: { dataLine in
                        guard let event = adapter.parseEvent(dataLine: dataLine) else {
                            DiagnosticLog.warning(.sse, "SSE event parse failed")
                            return
                        }
                        let shouldResetRetry = await MainActor.run { [weak owner] in
                            owner?.handleStreamEvent(event) ?? false
                        }
                        if shouldResetRetry {
                            retryAttempt = 0
                        }
                    }
                )
                await MainActor.run { [weak owner] in
                    owner?.applyConnectionState(.disconnected)
                }
            } catch is CancellationError {
                await MainActor.run { [weak owner] in
                    owner?.applyConnectionState(.idle)
                }
                return
            } catch {
                DiagnosticLog.warning(.sse, "foreground SSE stream failed error=\(String(describing: error))")
                await MainActor.run { [weak owner] in
                    owner?.applyConnectionState(wasConnected ? .reconnecting : .disconnected)
                }
            }

            retryAttempt += 1
            let backoffMs = min(10_000, 1_000 * retryAttempt)
            DiagnosticLog.debug(.sse, "foreground SSE retry in \(backoffMs)ms")
            try? await Task.sleep(for: .milliseconds(backoffMs))
        }
    }
}
