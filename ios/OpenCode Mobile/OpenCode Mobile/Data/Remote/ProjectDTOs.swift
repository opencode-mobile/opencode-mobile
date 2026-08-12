import Foundation

struct HealthDTO: Codable, Sendable {
    let healthy: Bool?
    let version: String?
    let pid: Int?
}

struct ProjectDTO: Codable, Sendable {
    let id: String
    let worktree: String
    let vcs: String?
}
