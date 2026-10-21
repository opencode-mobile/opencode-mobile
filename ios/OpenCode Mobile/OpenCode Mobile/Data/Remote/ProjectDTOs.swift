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
    /// User-set project name (v1 `name`, v2 `name`). Absent on most projects.
    let name: String?
    /// Main checkout of the project. V2 location sends it; v1 `worktree` already is it.
    let canonical: String?

    init(
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
    let vcs: String?
    let time: ProjectTimeDTO?
}

/// Project `time` in epoch ms. v1 sends `created`/`updated`/`initialized`; v2 adds `active`, and
/// `archived` once the server archived a project whose directories are gone (v2.0.27+).
nonisolated struct ProjectTimeDTO: Codable, Sendable, Equatable {
    let created: Double?
    let updated: Double?
    let active: Double?
    let archived: Double?

    init(created: Double? = nil, updated: Double? = nil, active: Double? = nil, archived: Double? = nil) {
        self.created = created
        self.updated = updated
        self.active = active
        self.archived = archived
    }

    /// Most recent activity: `active` when present, else `updated`.
    var activityEpochMs: Int64? {
        (active ?? updated).map { Int64($0) }
    }
}
