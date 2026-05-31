import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private var cache: [String: SharedLiveObservation<[Session]>] = [:]

    init(sessionRepository: any SessionRepository, eventStream: any ForegroundEventStream) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
    }

    func observeSessions(directory: String) -> AsyncStream<[Session]> {
        if cache[directory] == nil {
            cache[directory] = SharedLiveObservation { yield in
                Task {
                    await self.runSessionLoop(directory: directory, yield: yield)
                }
            }
        }
        return cache[directory]!.stream()
    }

    private func runSessionLoop(directory: String, yield: @escaping ([Session]) -> Void) async {
        if let sessions = await loadSessions(directory: directory) {
            yield(sessions)
        }

        var refreshTask: Task<Void, Never>?
        defer { refreshTask?.cancel() }
        for await event in eventStream.events() {
            guard event.type == "stream.reconnected" || event.triggersHomeRefresh else { continue }
            refreshTask?.cancel()
            refreshTask = Task {
                try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
                guard !Task.isCancelled else { return }
                if let sessions = await loadSessions(directory: directory) {
                    yield(sessions)
                }
            }
        }
    }

    func clearCache() {
        cache.removeAll()
    }

    private func loadSessions(directory: String) async -> [Session]? {
        try? await sessionRepository.listSessions(directory: directory)
    }
}
