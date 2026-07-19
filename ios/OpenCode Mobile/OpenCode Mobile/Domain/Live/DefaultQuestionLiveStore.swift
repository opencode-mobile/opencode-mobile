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
        if let requests = try? await sessionRepository.listPendingQuestions(directory: directory) { yield(requests) }
        for await event in eventStream.events() {
            guard event.type == "question.asked" ||
                event.type == "question.replied" ||
                event.type == "question.rejected" ||
                event.type == "stream.reconnected"
            else { continue }
            if let requests = try? await sessionRepository.listPendingQuestions(directory: directory) { yield(requests) }
        }
    }

    func clearCache() { cache.removeAll() }
}
