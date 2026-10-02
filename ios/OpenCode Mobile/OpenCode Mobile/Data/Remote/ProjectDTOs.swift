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

    init(id: String, worktree: String? = nil, directory: String? = nil, vcs: String? = nil) {
        self.id = id
        self.worktree = worktree
        self.directory = directory
        self.vcs = vcs
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
}
