import Foundation

@MainActor
final class DefaultQuestionLiveStore: QuestionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let syncCoordinator: any SyncCoordinator
    private let activeDirectories = ActiveDirectorySet()
    private var cache: [String: SharedLiveObservation<[QuestionRequest]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared,
        syncCoordinator: (any SyncCoordinator)? = nil
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
        self.syncCoordinator = syncCoordinator ?? DefaultSyncCoordinator(
            connection: eventStream.asLiveConnection(),
            worldSignals: worldSignals,
            eventStream: eventStream
        )
    }

    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]> {
        resubscribingLiveStream { [weak self] in
            guard let self else { return AsyncStream { $0.finish() } }
            return self.observation(for: directory).stream()
        }
    }

    func invalidate(directory: String?) {
        let keys: [ProjectionKey]
        if let directory {
            keys = activeDirectories.contains(directory) ? [.questions(directory: directory)] : []
        } else {
            keys = activeDirectories.snapshot().map { .questions(directory: $0) }
        }
        let coordinator = syncCoordinator
        for key in keys {
            coordinator.invalidate(key)
            Task {
                await coordinator.ensureFresh(key)
            }
        }
    }

    func clearCache() {
        let stale = Array(cache.values)
        cache.removeAll()
        stale.forEach { $0.finish() }
    }

    private func observation(for directory: String) -> SharedLiveObservation<[QuestionRequest]> {
        if let existing = cache[directory], !existing.isTerminated {
            return existing
        }
        let sessionRepository = sessionRepository
        let eventStream = eventStream
        let syncCoordinator = syncCoordinator
        let activeDirectories = activeDirectories
        let observation = SharedLiveObservation<[QuestionRequest]> { yield in
            Task.detached {
                await Self.runLoop(
                    directory: directory,
                    sessionRepository: sessionRepository,
                    eventStream: eventStream,
                    syncCoordinator: syncCoordinator,
                    activeDirectories: activeDirectories,
                    yield: yield
                )
            }
        }
        cache[directory] = observation
        return observation
    }

    private nonisolated static func runLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        syncCoordinator: any SyncCoordinator,
        activeDirectories: ActiveDirectorySet,
        yield: @escaping @Sendable ([QuestionRequest]) -> Void
    ) async {
        activeDirectories.insert(directory)
        defer { activeDirectories.remove(directory) }
        let key = ProjectionKey.questions(directory: directory)
        let gate = LiveStoreRefreshGate()
        let lastEmitted = QuestionEmitCache()

        @Sendable func loadAndCommit() async throws {
            let generation = syncCoordinator.profileGeneration
            let outcome = await gate.refresh(
                load: { () async -> [QuestionRequest]? in
                    try? await sessionRepository.listPendingQuestions(directory: directory)
                },
                commit: { requests in
                    guard syncCoordinator.profileGeneration == generation else { return }
                    DiagnosticLog.info(.question, "question refresh count=\(requests.count)")
                    guard lastEmitted.shouldEmit(requests) else {
                        DiagnosticLog.info(.question, "question emit skip cause=unchanged")
                        return
                    }
                    yield(requests)
                }
            )
            if outcome == .failed {
                DiagnosticLog.warning(.question, "question refresh failed")
                throw URLError(.cannotConnectToHost)
            }
        }

        let registration = syncCoordinator.register(key: key, loader: loadAndCommit)
        syncCoordinator.visible(key, true)
        defer {
            syncCoordinator.visible(key, false)
            syncCoordinator.unregister(registration)
        }
        await syncCoordinator.ensureFresh(key)

        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await event in eventStream.events() {
                    guard event.type == "question.asked"
                        || event.type == "question.replied"
                        || event.type == "question.rejected"
                    else { continue }
                    Task {
                        try? await loadAndCommit()
                    }
                }
            }
            await group.next()
            group.cancelAll()
        }
    }
}

private nonisolated final class QuestionEmitCache: @unchecked Sendable {
    private let lock = NSLock()
    private var value: [QuestionRequest]?

    func shouldEmit(_ next: [QuestionRequest]) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if let value, value == next { return false }
        value = next
        return true
    }
}
