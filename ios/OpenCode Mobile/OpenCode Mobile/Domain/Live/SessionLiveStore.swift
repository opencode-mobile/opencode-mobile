import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
    func invalidate(directory: String?) async
    /// Every change to a listed session's known state (from any observed list). Emits the
    /// current state first when a list already holds it.
    func observeKnownSession(sessionId: String) -> AsyncStream<Session>
    /// Raises the session's viewed watermark locally (monotone) and republishes its list.
    func applySessionViewed(sessionId: String, idleAtEpochMs: Int64)
}

extension SessionLiveStore {
    func invalidate(directory: String?) async {}
    func observeKnownSession(sessionId _: String) -> AsyncStream<Session> { AsyncStream { $0.finish() } }
    func applySessionViewed(sessionId _: String, idleAtEpochMs _: Int64) {}
}
