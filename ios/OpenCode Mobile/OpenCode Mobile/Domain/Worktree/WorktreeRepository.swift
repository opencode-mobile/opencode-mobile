import Foundation

enum WorktreeLoad: Equatable, Sendable {
    case loaded(directories: [String], listsSessions: Bool)
    case unloaded
    case stopped
    case failed
}

protocol WorktreeRepository: Sendable {
    func load(addedPath: String, projectID: String?) async -> WorktreeLoad
    /// V2 list, then refresh, then list again. A failed refresh keeps the first list.
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad
    func refresh(projectID: String) async -> Bool
    func refetch(projectID: String) async -> WorktreeLoad
    func onDisconnected()
    func onConnection(key: String)
}

/// Tests and previews: never calls the server, so nothing is badged.
struct UnavailableWorktreeRepository: WorktreeRepository {
    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { .unloaded }
    func onDisconnected() {}
    func onConnection(key: String) {}
}

enum V1WorktreeHTTP: Sendable {
    case ok(String)
    case notFound
    case failed(Int)
}

protocol WorktreeRemote: Sendable {
    func listV2(projectID: String) async throws -> [String]
    func refreshV2(projectID: String) async throws
    /// V1 `GET /experimental/worktree?directory=`.
    func listV1(directory: String) async -> V1WorktreeHTTP
}
