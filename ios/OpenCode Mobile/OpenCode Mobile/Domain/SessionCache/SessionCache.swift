import Foundation

/// Deep Session Cache module used by Live Store, MessageSync, Settings, Clear All
/// Data, and remove-project. Persistence details stay behind ``SessionCacheStore``.
nonisolated protocol SessionCache: AnyObject, Sendable {
    func seedSessions(worktree: String) async -> [Session]?
    func rememberSessions(worktree: String, sessions: [Session]) async
    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]?
    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async
    func openMessages(sessionID: String, worktree: String) -> SessionMessageCacheHandle
    func purgeWorktree(serverBaseURL: String, worktree: String) async
    /// Everything cached for one removed Connection Profile, under its id and under its URL.
    func purgeConnection(profileID: String, serverBaseURL: String) async
    func clearAll() async
    func estimatedSizeBytes() async -> Int64
}

extension SessionCache {
    func seedActivity(directory: String) async -> [SessionActivityCacheEntry]? { nil }
    func rememberActivity(directory: String, entries: [SessionActivityCacheEntry]) async {}
    func purgeConnection(profileID: String, serverBaseURL: String) async {}
}

nonisolated protocol SessionMessageCacheHandle: AnyObject, Sendable {
    func seed() async -> [Message]?
    func note(messages: [Message])
    func flush() async
    func close()
}
