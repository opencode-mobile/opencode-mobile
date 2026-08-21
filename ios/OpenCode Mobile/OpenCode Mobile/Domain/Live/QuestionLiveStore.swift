import Foundation

@MainActor
protocol QuestionLiveStore: AnyObject, Sendable {
    func observeQuestions(directory: String) -> AsyncStream<[QuestionRequest]>
    func clearCache()
    func invalidate(directory: String?)
}

extension QuestionLiveStore {
    func invalidate(directory: String?) {}
}
