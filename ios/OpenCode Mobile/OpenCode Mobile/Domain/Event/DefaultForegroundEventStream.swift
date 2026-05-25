import Foundation

@MainActor
final class DefaultForegroundEventStream: ForegroundEventStream {
    private(set) var connectionState: EventStreamConnectionState = .idle

    private let api: EventsAPIClient
    private let adapter: EventDomainAdapter
    private var streamTask: Task<Void, Never>?
    private var wasConnected = false
    private var subscriberContinuations: [UUID: AsyncStream<OpencodeEvent>.Continuation] = [:]

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

    func start() {
        guard streamTask == nil else { return }
        streamTask = Task { [weak self] in
            await self?.runStreamLoop()
        }
    }

    func stop() {
        streamTask?.cancel()
        streamTask = nil
        connectionState = .idle
    }

    private func publish(_ event: OpencodeEvent) {
        for continuation in subscriberContinuations.values {
            continuation.yield(event)
        }
    }

    private func runStreamLoop() async {
        var retryAttempt = 0
        while !Task.isCancelled {
            connectionState = wasConnected ? .reconnecting : .connecting
            do {
                try await api.streamGlobalEvents { [weak self] dataLine in
                    guard let self else { return }
                    guard let event = adapter.parseEvent(dataLine: dataLine) else { return }
                    switch event.type {
                    case "server.connected":
                        connectionState = .connected
                        if wasConnected {
                            publish(OpencodeEvent(type: "stream.reconnected", sessionID: nil))
                        }
                        wasConnected = true
                        retryAttempt = 0
                    case "server.heartbeat":
                        break
                    default:
                        publish(event)
                    }
                }
                connectionState = .disconnected
            } catch is CancellationError {
                connectionState = .idle
                return
            } catch {
                connectionState = wasConnected ? .reconnecting : .disconnected
            }

            retryAttempt += 1
            let backoffMs = min(10_000, 1_000 * retryAttempt)
            try? await Task.sleep(for: .milliseconds(backoffMs))
        }
    }
}
