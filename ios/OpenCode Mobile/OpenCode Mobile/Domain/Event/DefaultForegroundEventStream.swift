import Foundation

@MainActor
final class DefaultForegroundEventStream: ForegroundEventStream {
    private(set) var connectionState: EventStreamConnectionState = .idle

    private let api: EventsAPIClient
    private let adapter: EventDomainAdapter
    private var streamTask: Task<Void, Never>?
    private var wasConnected = false
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
        let api = api
        let adapter = adapter
        streamTask = Task.detached(priority: .utility) { [weak self] in
            await self?.runStreamLoop(api: api, adapter: adapter)
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
        wasConnected = false
        applyConnectionState(.idle)
    }

    private func publish(_ event: OpencodeEvent) {
        for continuation in subscriberContinuations.values {
            continuation.yield(event)
        }
    }

    private func applyConnectionState(_ state: EventStreamConnectionState) {
        connectionState = state
        for continuation in connectionStateContinuations.values {
            continuation.yield(state)
        }
    }

    @discardableResult
    private func handleStreamEvent(_ event: OpencodeEvent) -> Bool {
        switch event.type {
        case "server.connected":
            applyConnectionState(.connected)
            if wasConnected {
                publish(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
            }
            wasConnected = true
            return true
        case "server.heartbeat":
            return false
        default:
            publish(event)
            return false
        }
    }

    private func runStreamLoop(api: EventsAPIClient, adapter: EventDomainAdapter) async {
        var retryAttempt = 0
        while !Task.isCancelled {
            await MainActor.run {
                applyConnectionState(wasConnected ? .reconnecting : .connecting)
            }

            do {
                try await api.streamGlobalEvents(
                    onHTTPResponse: { _ in
                        await MainActor.run { [weak self] in
                            guard let self else { return }
                            if connectionState != .connected {
                                applyConnectionState(.connecting)
                            }
                        }
                    },
                    onDataLine: { [adapter] dataLine in
                        guard let event = adapter.parseEvent(dataLine: dataLine) else {
                            return
                        }
                        let shouldResetRetry = await MainActor.run { [weak self] in
                            self?.handleStreamEvent(event) ?? false
                        }
                        if shouldResetRetry {
                            retryAttempt = 0
                        }
                    }
                )
                await MainActor.run {
                    applyConnectionState(.disconnected)
                }
            } catch is CancellationError {
                await MainActor.run {
                    applyConnectionState(.idle)
                }
                return
            } catch {
                await MainActor.run {
                    applyConnectionState(wasConnected ? .reconnecting : .disconnected)
                }
            }

            retryAttempt += 1
            let backoffMs = min(10_000, 1_000 * retryAttempt)
            try? await Task.sleep(for: .milliseconds(backoffMs))
        }
    }
}
