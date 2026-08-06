import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
}
