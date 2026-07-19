import Foundation

@MainActor
protocol QuestionLiveStore: AnyObject, Sendable {
    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]>
    func clearCache()
}
