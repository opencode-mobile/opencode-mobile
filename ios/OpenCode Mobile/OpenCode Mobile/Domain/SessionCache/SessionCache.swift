import Foundation

/// Deep Session Cache module used by Live Store, MessageSync, Settings, Clear All
/// Data, and remove-project. Persistence details stay behind ``SessionCacheStore``.
nonisolated protocol SessionCache: AnyObject, Sendable {
    func seedSessions(worktree: String) async -> [Session]?
    /// Cached list for `worktree` under `connectionID` (a Connection Profile id), bound or not.
    func seedSessions(worktree: String, connectionID: String) async -> [Session]?
    func rememberSessions(worktree: String, sessions: [Session]) async
    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]?
    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async
    /// The server project `worktree` last resolved to, so a cold Home groups it as the live one will.
    func seedProjectResolution(worktree: String, connectionID: String?) async -> HomeProjectResolution?
    /// Queues the write under the connection active now and returns; writes land in call order,
    /// and a purge of the worktree or connection drops one still queued.
    func rememberProjectResolution(worktree: String, resolution: HomeProjectResolution)
    func openMessages(sessionID: String, worktree: String) -> SessionMessageCacheHandle
    func purgeWorktree(serverBaseURL: String, worktree: String) async
    /// Everything cached for one removed Connection Profile, under its id and under its URL.
    func purgeConnection(profileID: String, serverBaseURL: String) async
    func clearAll() async
    func estimatedSizeBytes() async -> Int64
}

extension SessionCache {
    func seedSessions(worktree: String, connectionID: String) async -> [Session]? { nil }
    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]? { nil }
    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async {}
    func seedProjectResolution(worktree: String, connectionID: String?) async -> HomeProjectResolution? { nil }
    func rememberProjectResolution(worktree: String, resolution: HomeProjectResolution) {}
    func purgeConnection(profileID: String, serverBaseURL: String) async {}
}

nonisolated protocol SessionMessageCacheHandle: AnyObject, Sendable {
    func seed() async -> [Message]?
    func note(messages: [Message])
    func flush() async
    func close()
}
