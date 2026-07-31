import Foundation
import OSLog

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
        OpenCodeLog.sse.info("foreground SSE stream starting")
        _ = ConnectionEpoch.shared.bump()
        let api = api
        let adapter = adapter
        streamTask = Task { @MainActor [weak self] in
            await self?.runStreamLoop(api: api, adapter: adapter)
        }
    }

    func stop() {
        if streamTask != nil || connectionState != .idle {
            OpenCodeLog.sse.info("foreground SSE stream stopping")
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
            OpenCodeLog.sse.debug("foreground SSE state \(String(describing: self.connectionState), privacy: .public) -> \(String(describing: state), privacy: .public)")
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
            OpenCodeLog.sse.info("foreground SSE stream reconnected")
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
            OpenCodeLog.sse.info(
                "SSE event type=\(event.type, privacy: .public) session=\(event.sessionID ?? "-", privacy: .public)"
            )
        default:
            OpenCodeLog.sse.debug(
                "SSE event type=\(event.type, privacy: .public) session=\(event.sessionID ?? "-", privacy: .public)"
            )
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

    private func runStreamLoop(api: EventsAPIClient, adapter: EventDomainAdapter) async {
        var retryAttempt = 0
        while !Task.isCancelled {
            await MainActor.run {
                currentStreamWasConnected = false
                emittedReconnectForCurrentStream = false
                applyConnectionState(wasConnected ? .reconnecting : .connecting)
            }

            do {
                try await api.streamGlobalEvents(
                    onHTTPResponse: { statusCode in
                        OpenCodeLog.sse.info("GET /global/event opened status=\(statusCode)")
                        await MainActor.run { [weak self] in
                            guard let self else { return }
                            markStreamConnected()
                        }
                        retryAttempt = 0
                    },
                    onDataLine: { [adapter] dataLine in
                        guard let event = adapter.parseEvent(dataLine: dataLine) else {
                            OpenCodeLog.sse.warning("SSE event parse failed")
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
                OpenCodeLog.sse.warning("foreground SSE stream failed error=\(String(describing: error), privacy: .public)")
                await MainActor.run {
                    applyConnectionState(wasConnected ? .reconnecting : .disconnected)
                }
            }

            retryAttempt += 1
            let backoffMs = min(10_000, 1_000 * retryAttempt)
            OpenCodeLog.sse.debug("foreground SSE retry in \(backoffMs)ms")
            try? await Task.sleep(for: .milliseconds(backoffMs))
        }
    }
}
