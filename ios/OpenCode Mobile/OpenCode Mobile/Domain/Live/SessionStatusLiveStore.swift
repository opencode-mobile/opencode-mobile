import Foundation

@MainActor
protocol SessionStatusLiveStore: AnyObject, Sendable {
    func observeStatuses(directory: String) -> AsyncStream<[String: SessionStatus]>
    func clearCache()
    func invalidate(directory: String?) async
    func markBusy(directory: String, sessionID: String)
    func clearLocalBusy(directory: String, sessionID: String)
}

extension SessionStatusLiveStore {
    func invalidate(directory: String?) async {}
    func markBusy(directory: String, sessionID: String) {}
    func clearLocalBusy(directory: String, sessionID: String) {}
}
