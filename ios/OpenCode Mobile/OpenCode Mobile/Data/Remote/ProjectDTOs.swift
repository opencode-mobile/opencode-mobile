import Foundation

struct HealthDTO: Codable, Sendable {
    let healthy: Bool?
    let version: String?
    let pid: Int?
}

struct ProjectDTO: Codable, Sendable {
    let id: String
    let worktree: String?
    /// V2 location project uses `directory` instead of `worktree`.
    let directory: String?
    let vcs: String?
    let time: ProjectTimeDTO?

    init(
        id: String,
        worktree: String? = nil,
        directory: String? = nil,
        vcs: String? = nil,
        time: ProjectTimeDTO? = nil
    ) {
        self.id = id
        self.worktree = worktree
        self.directory = directory
        self.vcs = vcs
        self.time = time
    }

    var resolvedWorktree: String {
        if let worktree, !worktree.isEmpty { return worktree }
        if let directory, !directory.isEmpty { return directory }
        return ""
    }
}

/// v2 `GET /api/project` item: the project root is `canonical`.
nonisolated struct V2ProjectDTO: Decodable, Sendable {
    let id: String
    let canonical: String
    let name: String?
    let time: ProjectTimeDTO?
}

/// Project `time` in epoch ms. v1 sends `created`/`updated`/`initialized`; v2 adds `active`.
nonisolated struct ProjectTimeDTO: Codable, Sendable, Equatable {
    let created: Double?
    let updated: Double?
    let active: Double?

    init(created: Double? = nil, updated: Double? = nil, active: Double? = nil) {
        self.created = created
        self.updated = updated
        self.active = active
    }

    /// Most recent activity: `active` when present, else `updated`.
    var activityEpochMs: Int64? {
        (active ?? updated).map { Int64($0) }
    }
}
