import Foundation

/// Test / optional injection default that never reads or writes disk.
final class NoOpSessionCache: SessionCache, @unchecked Sendable {
    static let shared = NoOpSessionCache()

    func seedSessions(worktree: String) async -> [Session]? { nil }
    func rememberSessions(worktree: String, sessions: [Session]) async {}
    func openMessages(sessionID: String, worktree: String) -> any SessionMessageCacheHandle {
        NoOpSessionMessageCacheHandle()
    }
    func purgeWorktree(serverBaseURL: String, worktree: String) async {}
    func clearAll() async {}
    func estimatedSizeBytes() async -> Int64 { 0 }
}

private final class NoOpSessionMessageCacheHandle: SessionMessageCacheHandle, @unchecked Sendable {
    func seed() async -> [Message]? { nil }
    func note(messages: [Message]) {}
    func flush() async {}
    func close() {}
}
