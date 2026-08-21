import Foundation

@MainActor
protocol SessionStatusLiveStore: AnyObject, Sendable {
    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]>
    func clearCache()
    func invalidate(directory: String?)
}

extension SessionStatusLiveStore {
    func invalidate(directory: String?) {}
}
