import Foundation

@MainActor
protocol SessionLiveStore: AnyObject, Sendable {
    /// The newest root sessions of `directory`, one window at a time (see ``loadMore(directory:)``).
    func observeSessions(directory: String) -> AsyncStream<SessionListSnapshot>
    func clearCache()
    func invalidate(directory: String?) async
    /// Grows `directory`'s window by ``HomeSessionPaging/pageSize`` and refetches it in one
    /// request. No-op while a load more runs or when the last window had nothing more.
    func loadMore(directory: String) async
    /// Parents learned on this profile, for rolling busy sessions up to their Home row.
    var ancestry: SessionAncestry { get }
    /// Every change to a listed session's known state (from any observed list). Emits the
    /// current state first when a list already holds it.
    func observeKnownSession(sessionId: String) -> AsyncStream<Session>
    /// Raises the session's viewed watermark locally (monotone) and republishes its list.
    func applySessionViewed(sessionId: String, idleAtEpochMs: Int64)
}

extension SessionLiveStore {
    func invalidate(directory: String?) async {}
    func loadMore(directory _: String) async {}
    var ancestry: SessionAncestry { SessionAncestry.unseeded }
    func observeKnownSession(sessionId _: String) -> AsyncStream<Session> { AsyncStream { $0.finish() } }
    func applySessionViewed(sessionId _: String, idleAtEpochMs _: Int64) {}
}

extension SessionAncestry {
    /// Knows nothing and fetches nothing: stores that keep no ancestry.
    nonisolated static let unseeded = SessionAncestry(fetch: nil)
}
