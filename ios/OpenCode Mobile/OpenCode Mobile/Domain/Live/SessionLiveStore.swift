import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<[Session]>
}
