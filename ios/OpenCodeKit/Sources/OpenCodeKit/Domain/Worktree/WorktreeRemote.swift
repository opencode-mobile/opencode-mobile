import Foundation

/// A checkout the server created.
public nonisolated struct CreatedWorktree: Equatable, Sendable {
    /// Canonical absolute path.
    public let directory: String
    /// Folder name the server used (it may differ from the requested name).
    public let name: String
    /// v1 `opencode/<name>`; nil on v2, whose checkout is detached.
    public let branch: String?

    public init(
        directory: String,
        name: String,
        branch: String?
    ) {
        self.directory = directory
        self.name = name
        self.branch = branch
    }
}

/// Failures of the worktree create and branch-list calls.
public nonisolated enum WorktreeRemoteError: Error, Equatable, Sendable {
    case noActiveConnection
    case invalidResponse
    case http(statusCode: Int, body: String)
}

public enum V1WorktreeHTTP: Sendable {
    case ok(String)
    case notFound
    case failed(Int)
}

public protocol WorktreeRemote: Sendable {
    func listV2(projectID: String) async throws -> [String]
    func refreshV2(projectID: String) async throws
    /// V1 `GET /experimental/worktree?directory=`.
    func listV1(directory: String) async -> V1WorktreeHTTP
    /// V2 `POST /api/worktree`; returns the new checkout's directory.
    func createV2(projectID: String, from: String, name: String?, branch: String?) async throws -> String
    /// V1 `POST /experimental/worktree?directory=`. A nil name lets the server pick one.
    func createV1(directory: String, name: String?) async throws -> CreatedWorktree
    /// V2 `GET /api/vcs/branch` at `directory`.
    func branchesV2(directory: String) async throws -> [String]
    /// V2 `DELETE /api/worktree` with `{projectID, directory, force}`. Throws `WorktreeRemoteError`.
    func removeV2(projectID: String, directory: String, force: Bool) async throws
    /// V1 `DELETE /experimental/worktree?directory=` with `{"directory"}`; the server always forces.
    func removeV1(projectDirectory: String, directory: String) async throws
}
