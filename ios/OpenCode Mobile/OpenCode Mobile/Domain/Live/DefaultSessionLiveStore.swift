import Foundation

@MainActor
final class DefaultSessionLiveStore: SessionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let sessionCache: any SessionCache
    private var cache: [String: SharedLiveObservation<SessionListSnapshot>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.sessionCache = sessionCache
    }

    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let sessionCache = sessionCache
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runSessionLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        sessionCache: sessionCache,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    private nonisolated static func runSessionLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        sessionCache: any SessionCache,
        yield: @escaping @Sendable (SessionListSnapshot) -> Void
    ) async {
        if let seeded = await sessionCache.seedSessions(worktree: directory) {
            yield(SessionListSnapshot(sessions: seeded, isFromCache: true))
        }

        if let sessions = await loadSessions(directory: directory, sessionRepository: sessionRepository) {
            await sessionCache.rememberSessions(worktree: directory, sessions: sessions)
            yield(SessionListSnapshot(sessions: sessions, isFromCache: false))
        }

        var refreshTask: Task<Void, Never>?
        defer { refreshTask?.cancel() }
        for await event in eventStream.events() {
            guard event.type == "stream.reconnected"
                || event.type == "server.connected"
                || event.triggersHomeRefresh
            else { continue }
            refreshTask?.cancel()
            refreshTask = Task {
                try? await Task.sleep(for: .milliseconds(LiveStoreRefreshPolicy.sessionDebounceMs))
                guard !Task.isCancelled else { return }
                if let sessions = await loadSessions(
                    directory: directory,
                    sessionRepository: sessionRepository
                ) {
                    await sessionCache.rememberSessions(worktree: directory, sessions: sessions)
                    yield(SessionListSnapshot(sessions: sessions, isFromCache: false))
                }
            }
        }
    }

    func clearCache() {
        cache.removeAll()
    }

    private nonisolated static func loadSessions(
        directory: String,
        sessionRepository: any SessionRepository
    ) async -> [Session]? {
        try? await sessionRepository.listSessions(directory: directory)
    }
}
