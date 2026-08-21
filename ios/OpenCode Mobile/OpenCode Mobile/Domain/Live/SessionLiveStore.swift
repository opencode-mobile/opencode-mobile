import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
    func invalidate(directory: String?)
}

extension SessionLiveStore {
    func invalidate(directory: String?) {}
}
