import Foundation

@MainActor
final class DefaultQuestionLiveStore: QuestionLiveStore {
    private let sessionRepository: any SessionRepository
    private let eventStream: any ForegroundEventStream
    private var cache: [String: SharedLiveObservation<[QuestionRequest]>] = [:]

    init(sessionRepository: any SessionRepository, eventStream: any ForegroundEventStream) {
        self.sessionRepository = sessionRepository
        self.eventStream = eventStream
    }

    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]> {
        if cache[directory] == nil {
            let sessionRepository = sessionRepository
            let eventStream = eventStream
            cache[directory] = SharedLiveObservation { yield in
                Task.detached {
                    await Self.runLoop(
                        directory: directory,
                        sessionRepository: sessionRepository,
                        eventStream: eventStream,
                        yield: yield
                    )
                }
            }
        }
        return cache[directory]!.stream()
    }

    private nonisolated static func runLoop(
        directory: String,
        sessionRepository: any SessionRepository,
        eventStream: any ForegroundEventStream,
        yield: @escaping @Sendable ([QuestionRequest]) -> Void
    ) async {
        await refresh(directory: directory, trigger: "initial", sessionRepository: sessionRepository, yield: yield)
        for await event in eventStream.events() {
            guard event.type == "question.asked" ||
                event.type == "question.replied" ||
                event.type == "question.rejected" ||
                event.type == "stream.reconnected" ||
                event.type == "server.connected"
            else { continue }
            await refresh(
                directory: directory,
                trigger: event.type,
                sessionRepository: sessionRepository,
                yield: yield
            )
        }
    }

    private nonisolated static func refresh(
        directory: String,
        trigger: String,
        sessionRepository: any SessionRepository,
        yield: @escaping @Sendable ([QuestionRequest]) -> Void
    ) async {
        do {
            let requests = try await sessionRepository.listPendingQuestions(directory: directory)
            DiagnosticLog.info(.question, "question refresh trigger=\(trigger) count=\(requests.count)")
            yield(requests)
        } catch {
            DiagnosticLog.warning(.question, "question refresh failed trigger=\(trigger)")
        }
    }

    func clearCache() { cache.removeAll() }
}
