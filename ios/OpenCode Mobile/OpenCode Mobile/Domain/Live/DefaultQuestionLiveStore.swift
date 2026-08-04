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
            cache[directory] = SharedLiveObservation { yield in
                Task { await self.runLoop(directory: directory, yield: yield) }
            }
        }
        return cache[directory]!.stream()
    }

    private func runLoop(directory: String, yield: @escaping ([QuestionRequest]) -> Void) async {
        await refresh(directory: directory, trigger: "initial", yield: yield)
        for await event in eventStream.events() {
            guard event.type == "question.asked" ||
                event.type == "question.replied" ||
                event.type == "question.rejected" ||
                event.type == "stream.reconnected" ||
                event.type == "server.connected"
            else { continue }
            await refresh(directory: directory, trigger: event.type, yield: yield)
        }
    }

    private func refresh(
        directory: String,
        trigger: String,
        yield: @escaping ([QuestionRequest]) -> Void
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
