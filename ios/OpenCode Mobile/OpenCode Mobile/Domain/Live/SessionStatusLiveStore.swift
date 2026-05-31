import Foundation

@MainActor
protocol SessionStatusLiveStore: AnyObject, Sendable {
    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]>
    func clearCache()
}
