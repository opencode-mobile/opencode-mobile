import Foundation

public struct HealthDTO: Codable, Sendable {
    public let healthy: Bool?
    public let version: String?
    public let pid: Int?

    public init(
        healthy: Bool?,
        version: String?,
        pid: Int?
    ) {
        self.healthy = healthy
        self.version = version
        self.pid = pid
    }
}

public struct ProjectDTO: Codable, Sendable {
    public let id: String
    public let worktree: String?
    /// V2 location project uses `directory` instead of `worktree`.
    public let directory: String?
    public let vcs: String?
    public let time: ProjectTimeDTO?
    /// User-set project name (v1 `name`, v2 `name`). Absent on most projects.
    public let name: String?
    /// Main checkout of the project. V2 location sends it; v1 `worktree` already is it.
    public let canonical: String?

    public init(
        id: String,
        worktree: String? = nil,
        directory: String? = nil,
        vcs: String? = nil,
        time: ProjectTimeDTO? = nil,
        name: String? = nil,
        canonical: String? = nil
    ) {
        self.id = id
        self.worktree = worktree
        self.directory = directory
        self.vcs = vcs
        self.time = time
        self.name = name
        self.canonical = canonical
    }

    public var resolvedWorktree: String {
        if let worktree, !worktree.isEmpty { return worktree }
        if let directory, !directory.isEmpty { return directory }
        return ""
    }
}

/// v2 `GET /api/project` item: the project root is `canonical`.
public nonisolated struct V2ProjectDTO: Decodable, Sendable {
    public let id: String
    public let canonical: String
    public let name: String?
    public let vcs: String?
    public let time: ProjectTimeDTO?

    public init(
        id: String,
        canonical: String,
        name: String?,
        vcs: String?,
        time: ProjectTimeDTO?
    ) {
        self.id = id
        self.canonical = canonical
        self.name = name
        self.vcs = vcs
        self.time = time
    }
}

/// Project `time` in epoch ms. v1 sends `created`/`updated`/`initialized`; v2 adds `active`, and
/// `archived` once the server archived a project whose directories are gone (v2.0.27+).
public nonisolated struct ProjectTimeDTO: Codable, Sendable, Equatable {
    public let created: Double?
    public let updated: Double?
    public let active: Double?
    public let archived: Double?

    public init(created: Double? = nil, updated: Double? = nil, active: Double? = nil, archived: Double? = nil) {
        self.created = created
        self.updated = updated
        self.active = active
        self.archived = archived
    }

    /// Most recent activity: `active` when present, else `updated`.
    public var activityEpochMs: Int64? {
        (active ?? updated).map { Int64($0) }
    }
}
