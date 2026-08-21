import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
    func invalidate(directory: String?) async
}

extension SessionLiveStore {
    func invalidate(directory: String?) async {}
}
