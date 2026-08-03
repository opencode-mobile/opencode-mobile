import Foundation

@MainActor
final class DefaultSessionStatusLiveStore: SessionStatusLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private var cache: [String: SharedLiveObservation<[String: SessionStatus]>] = [:]

    init(sessionRepository: any SessionRepository, eventStream: any ForegroundEventStream) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
    }

    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]> {
        if cache[directory] == nil {
            cache[directory] = SharedLiveObservation { yield in
                Task {
                    await self.runStatusLoop(directory: directory, yield: yield)
                }
            }
        }
        return cache[directory]!.stream()
    }

    private func runStatusLoop(directory: String, yield: @escaping ([String: SessionStatus]) -> Void) async {
        if let statuses = await loadStatuses(directory: directory) {
            yield(statuses)
        }

        var refreshTask: Task<Void, Never>?
        defer { refreshTask?.cancel() }
        for await event in eventStream.events() {
            guard event.type == "stream.reconnected"
                || event.type == "server.connected"
                || event.triggersStatusRefresh
            else { continue }
            refreshTask?.cancel()
            refreshTask = Task {
                try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.statusDebounceMs))
                guard !Task.isCancelled else { return }
                if let statuses = await loadStatuses(directory: directory) {
                    yield(statuses)
                }
            }
        }
    }

    func clearCache() {
        cache.removeAll()
    }

    private func loadStatuses(directory: String) async -> [String: SessionStatus]? {
        guard let statuses = try? await sessionRepository.getAllSessionStatuses(directory: directory) else {
            return nil
        }
        return Dictionary(uniqueKeysWithValues: statuses.map { ($0.sessionID, $0) })
    }
}
