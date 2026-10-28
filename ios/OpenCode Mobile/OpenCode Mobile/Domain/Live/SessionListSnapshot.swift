import Foundation

nonisolated struct SessionListSnapshot: Sendable, Equatable {
    let sessions: [Session]
    let isFromCache: Bool
    /// True on the publish that reports a failed server load for this directory. `sessions`
    /// then holds whatever was already known and `isFromCache` is true. A later successful
    /// load publishes with this cleared.
    let loadFailed: Bool

    nonisolated init(sessions: [Session], isFromCache: Bool = false, loadFailed: Bool = false) {
        self.sessions = sessions
        self.isFromCache = isFromCache
        self.loadFailed = loadFailed
    }
}
