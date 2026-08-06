import Foundation

/// Deep Session Cache module used by Live Store, MessageSync, Settings, Clear All
/// Data, and remove-project. Persistence details stay behind ``SessionCacheStore``.
nonisolated protocol SessionCache: AnyObject, Sendable {
    func seedSessions(worktree: String) async -> [Session]?
    func rememberSessions(worktree: String, sessions: [Session]) async
    func openMessages(sessionID: String, worktree: String) -> SessionMessageCacheHandle
    func purgeWorktree(serverBaseURL: String, worktree: String) async
    func clearAll() async
    func estimatedSizeBytes() async -> Int64
}

nonisolated protocol SessionMessageCacheHandle: AnyObject, Sendable {
    func seed() async -> [Message]?
    func note(messages: [Message])
    func flush() async
    func close()
}
