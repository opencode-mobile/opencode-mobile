import Foundation

nonisolated struct SessionListSnapshot: Sendable, Equatable {
    let sessions: [Session]
    let isFromCache: Bool
    /// True on the publish that reports a failed server load for this directory. `sessions`
    /// then holds whatever was already known and `isFromCache` is true. A later successful
    /// load publishes with this cleared.
    let loadFailed: Bool
    /// `sessions` is a window of the newest roots and the server may hold older ones. A cache
    /// seed says false; a failed load keeps the last server answer.
    let hasMore: Bool

    nonisolated init(sessions: [Session], isFromCache: Bool = false, loadFailed: Bool = false, hasMore: Bool = false) {
        self.sessions = sessions
        self.isFromCache = isFromCache
        self.loadFailed = loadFailed
        self.hasMore = hasMore
    }
}
