import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let eventAdapter: EventDomainAdapter
    private var cache: [String: SharedLiveObservation<[String: SessionStatus]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter = EventDomainAdapter()
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.eventAdapter = eventAdapter
    }

    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let eventAdapter = eventAdapter
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runStatusLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        eventAdapter: eventAdapter,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    private nonisolated static func runStatusLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        eventAdapter: EventDomainAdapter,
        yield: @escaping @Sendable ([String: SessionStatus]) -> Void
    ) async {
        var current: [String: SessionStatus] = [:]
        if let statuses = await loadStatuses(directory: directory, sessionRepository: sessionRepository) {
            current = statuses
            yield(current)
        }

        var reconnectTask: Task<Void, Never>?
        defer { reconnectTask?.cancel() }
        for await event in eventStream.events() {
            if event.type == "stream.reconnected" || event.type == "server.connected" {
                reconnectTask?.cancel()
                reconnectTask = Task {
                    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                    guard !Task.isCancelled else { return }
                    if let statuses = await loadStatuses(
                        directory: directory,
                        sessionRepository: sessionRepository
                    ) {
                        current = statuses
                        yield(current)
                    }
                }
                continue
            }
            if event.type == "session.active.snapshot" {
                reconnectTask?.cancel()
                reconnectTask = Task {
                    try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                    guard !Task.isCancelled else { return }
                    if let statuses = await loadStatuses(
                        directory: directory,
                        sessionRepository: sessionRepository
                    ) {
                        current = statuses
                        yield(current)
                    }
                }
                continue
            }
            guard event.triggersStatusRefresh else { continue }
            guard let next = applyStatusEvent(current: current, event: event, eventAdapter: eventAdapter),
                  next != current
            else { continue }
            current = next
            yield(current)
        }
    }

    func clearCache() {
        cache.removeAll()
    }

    private nonisolated static func loadStatuses(
        directory: String,
        sessionRepository: any SessionRepository
    ) async -> [String: SessionStatus]? {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: directory) else {
            return nil
        }
        return Dictionary(uniqueKeysWithValues: statuses.map { ($0.sessionID, $0) })
    }

    private nonisolated static func applyStatusEvent(
        current: [String: SessionStatus],
        event: OpencodeEvent,
        eventAdapter: EventDomainAdapter
    ) -> [String: SessionStatus]? {
        switch event.type {
        case "session.status":
            guard let status = eventAdapter.sessionStatus(from: event) else { return nil }
            if status.raw["type"] == "idle" {
                guard current[status.sessionID] != nil else { return nil }
                var next = current
                next.removeValue(forKey: status.sessionID)
                return next
            }
            var next = current
            next[status.sessionID] = status
            return next
        case "session.idle":
            guard let sessionID = event.sessionID, current[sessionID] != nil else { return nil }
            var next = current
            next.removeValue(forKey: sessionID)
            return next
        default:
            return nil
        }
    }
}
