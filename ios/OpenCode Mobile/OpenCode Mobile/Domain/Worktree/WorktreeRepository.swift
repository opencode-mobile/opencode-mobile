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
    /// Creates a checkout: v2 `POST /api/worktree`, v1 `POST /experimental/worktree`.
    /// Throws `WorktreeCreateError` with the words to show.
    func create(_ request: CreateWorktreeRequest) async throws -> CreatedWorktree
    /// Refs a v2 worktree can start from (`GET /api/vcs/branch`); v1 picks no base, so empty.
    func baseBranches(directory: String) async throws -> [String]
    /// False once this connection's v1 server answered 404 for its worktree routes.
    var canCreateWorktrees: Bool { get }
}

/// What New worktree asks the server for. v1 ignores `projectID` and `baseRef`.
nonisolated struct CreateWorktreeRequest: Equatable, Sendable {
    let projectID: String?
    /// Checkout the new one is made from (v2 `from`, v1 `directory`).
    let sourceDirectory: String
    let name: String
    /// v2 start ref; nil starts from the source checkout's HEAD.
    let baseRef: String?
}

/// A checkout the server created.
nonisolated struct CreatedWorktree: Equatable, Sendable {
    /// Canonical absolute path.
    let directory: String
    /// Folder name the server used (it may differ from the requested name).
    let name: String
    /// v1 `opencode/<name>`; nil on v2, whose checkout is detached.
    let branch: String?
}

nonisolated struct WorktreeCreateError: Error, Equatable, Sendable {
    let message: String
}

/// Failures of the worktree create and branch-list calls.
nonisolated enum WorktreeRemoteError: Error, Equatable, Sendable {
    case noActiveConnection
    case invalidResponse
    case http(statusCode: Int, body: String)
}

/// Tests and previews: never calls the server, so nothing is badged.
struct UnavailableWorktreeRepository: WorktreeRepository {
    func load(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func loadRefreshed(addedPath: String, projectID: String?) async -> WorktreeLoad { .unloaded }
    func refresh(projectID: String) async -> Bool { false }
    func refetch(projectID: String) async -> WorktreeLoad { .unloaded }
    func onDisconnected() {}
    func onConnection(key: String) {}
    func create(_ request: CreateWorktreeRequest) async throws -> CreatedWorktree {
        throw WorktreeCreateError(message: WorktreeCreateCopy.unsupported)
    }
    func baseBranches(directory: String) async throws -> [String] { [] }
    var canCreateWorktrees: Bool { false }
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
    /// V2 `POST /api/worktree`; returns the new checkout's directory.
    func createV2(projectID: String, from: String, name: String, branch: String?) async throws -> String
    /// V1 `POST /experimental/worktree?directory=`.
    func createV1(directory: String, name: String) async throws -> CreatedWorktree
    /// V2 `GET /api/vcs/branch` at `directory`.
    func branchesV2(directory: String) async throws -> [String]
}
