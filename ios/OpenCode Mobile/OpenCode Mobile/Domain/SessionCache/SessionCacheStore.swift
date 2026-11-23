import Foundation

/// Internal persistence seam for SwiftData / in-memory adapters.
/// Not injected into Live Store or MessageSync — use ``SessionCache`` instead.
nonisolated protocol SessionCacheStore: Sendable {
    func load(key: SessionCacheKey) async -> SessionCacheRecord?
    func commit(key: SessionCacheKey, value: SessionCacheValue, meta: SessionCacheCommitMeta) async
    func retain(key: SessionCacheKey, reason: SessionCacheRetainReason, atEpochMs: Int64) async
    func evict(_ selector: SessionCacheEvictSelector) async
    func report(_ selector: SessionCacheEvictSelector) async -> SessionCacheReport
    /// Keys of every message timeline row, payload or not (an opened session retains an empty
    /// row). Nil when the store cannot list them; the thumbnail sweep then skips.
    func messageTimelineKeys() async -> [SessionCacheKey]?
    /// Deletes the least recently used message timelines of `connectionId` past `keeping`, and
    /// returns their keys. Recency is the later of the last open and the last write.
    func evictLeastRecentTimelines(connectionId: String, keeping: Int) async -> [SessionCacheKey]
}

extension SessionCacheStore {
    func messageTimelineKeys() async -> [SessionCacheKey]? { nil }
    func evictLeastRecentTimelines(connectionId _: String, keeping _: Int) async -> [SessionCacheKey] { [] }
}

nonisolated enum SessionCacheKind: String, Sendable {
    case sessionList = "SessionList"
    case sessionActivity = "SessionActivity"
    case messageTimeline = "MessageTimeline"
    case projectResolution = "ProjectResolution"
    case worktreeLayout = "WorktreeLayout"
}

nonisolated struct SessionCacheKey: Sendable, Hashable {
    let connectionId: String
    let worktree: String
    let kind: SessionCacheKind
    let entryId: String

    init(
        connectionId: String,
        worktree: String,
        kind: SessionCacheKind,
        entryId: String = ""
    ) {
        self.connectionId = connectionId
        self.worktree = worktree
        self.kind = kind
        self.entryId = entryId
    }

    var cacheKey: String {
        switch kind {
        case .sessionList:
            "\(connectionId)|\(worktree)|list"
        case .sessionActivity:
            "\(connectionId)|\(worktree)|activity"
        case .messageTimeline:
            "\(connectionId)|\(worktree)|msg|\(entryId)"
        case .projectResolution:
            "\(connectionId)|\(worktree)|project"
        case .worktreeLayout:
            "\(connectionId)|\(worktree)|worktrees"
        }
    }
}

nonisolated struct SessionCacheValue: Sendable, Equatable {
    let payloadJSON: String
    let schemaVersion: Int
}

nonisolated struct SessionCacheRecord: Sendable, Equatable {
    let value: SessionCacheValue
    let syncedAtEpochMs: Int64
    let retainedAtEpochMs: Int64?
}

nonisolated struct SessionCacheCommitMeta: Sendable, Equatable {
    let syncedAtEpochMs: Int64
}

nonisolated enum SessionCacheRetainReason: Sendable {
    case opened
}

nonisolated enum SessionCacheEvictSelector: Sendable {
    case all
    case worktree(connectionIdOrUrlPrefix: String, worktree: String, matchByUrlPrefix: Bool)
    /// Every worktree of one connection: by profile id, or by `normalizedBaseURL|` prefix.
    case connection(connectionIdOrUrlPrefix: String, matchByUrlPrefix: Bool)
}

nonisolated struct SessionCacheReport: Sendable, Equatable {
    let totalBytes: Int64
    let entryCount: Int64
}
