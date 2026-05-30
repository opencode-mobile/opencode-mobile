import Foundation

protocol SessionStatusLiveStore: AnyObject, Sendable {
    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]>
}
