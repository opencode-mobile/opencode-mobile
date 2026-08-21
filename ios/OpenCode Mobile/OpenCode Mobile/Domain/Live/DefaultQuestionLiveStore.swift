import Foundation

@MainActor
final class DefaultQuestionLiveStore: QuestionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private let worldSignals: any WorldSignals
    private let invalidationBus = StoreInvalidationBus()
    private var cache: [String: SharedLiveObservation<[QuestionRequest]>] = [:]

    init(
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals = SilentWorldSignals.shared
    ) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
        self.worldSignals = worldSignals
    }

    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            let worldSignals = worldSignals
            let invalidationBus = invalidationBus
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        worldSignals: worldSignals,
                        invalidationBus: invalidationBus,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    func invalidate(directory: String?) {
        invalidationBus.invalidate(key: directory, reason: StoreRefreshReason.pullToRefresh)
    }

    func clearCache() { cache.removeAll() }

    private nonisolated static func runLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        worldSignals: any WorldSignals,
        invalidationBus: StoreInvalidationBus,
        yield: @escaping @Sendable ([QuestionRequest]) -> Void
    ) async {
        let stale = StaleBit()
        await refresh(
            directory: directory,
            reason: StoreRefreshReason.initialSubscribe,
            force: true,
            stale: stale,
            sessionRepository: sessionRepository,
            yield: yield
        )
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await signal in worldSignals.signals() {
                    if signal.marksStale {
                        stale.mark()
                        continue
                    }
                    guard let reason = signal.catchUpReason else { continue }
                    await refresh(
                        directory: directory,
                        reason: reason,
                        force: false,
                        stale: stale,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
                }
            }
            group.addTask {
                for await invalidation in invalidationBus.events() {
                    if let key = invalidation.key, key != directory { continue }
                    stale.mark()
                    await refresh(
                        directory: directory,
                        reason: invalidation.reason,
                        force: true,
                        stale: stale,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
                }
            }
            group.addTask {
                for await event in eventStream.events() {
                    guard event.type == "question.asked" ||
                        event.type == "question.replied" ||
                        event.type == "question.rejected" ||
                        event.type == "stream.reconnected" ||
                        event.type == "server.connected"
                    else { continue }
                    await refresh(
                        directory: directory,
                        reason: event.type,
                        force: true,
                        stale: stale,
                        sessionRepository: sessionRepository,
                        yield: yield
                    )
                }
            }
            await group.next()
            group.cancelAll()
        }
    }

    private nonisolated static func refresh(
        directory: String,
        reason: String,
        force: Bool,
        stale: StaleBit,
        sessionRepository: any SessionRepository,
        yield: @escaping @Sendable ([QuestionRequest]) -> Void
    ) async {
        if !force, !stale.isSet {
            DiagnosticLog.info(.question, "question skip reason=\(reason)")
            return
        }
        do {
            let requests = try await sessionRepository.listPendingQuestions(directory: directory)
            stale.clear()
            DiagnosticLog.info(.question, "question refresh reason=\(reason) count=\(requests.count)")
            yield(requests)
        } catch {
            DiagnosticLog.warning(.question, "question refresh failed reason=\(reason)")
        }
    }
}
