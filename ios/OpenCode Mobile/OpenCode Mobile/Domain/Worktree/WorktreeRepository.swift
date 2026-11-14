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
    /// Deletes a checkout on the server: v2 `DELETE /api/worktree` (needs `projectID`), v1
    /// `DELETE /experimental/worktree`. Throws `WorktreeRemoveError`; `.forceRequired` when v2
    /// found uncommitted changes.
    func remove(_ request: RemoveWorktreeRequest) async throws
}

extension WorktreeRepository {
    func remove(_ request: RemoveWorktreeRequest) async throws {
        throw WorktreeRemoveError.unsupported
    }
}

/// What New worktree asks the server for. v1 ignores `projectID` and `baseRef`.
nonisolated struct CreateWorktreeRequest: Equatable, Sendable {
    let projectID: String?
    /// Checkout the new one is made from (v2 `from`, v1 `directory`).
    let sourceDirectory: String
    /// Nil (or blank) lets the server name the checkout.
    let name: String?
    /// v2 start ref; nil starts from the source checkout's HEAD.
    let baseRef: String?
}

nonisolated struct WorktreeCreateError: Error, Equatable, Sendable {
    let message: String
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
        throw WorktreeCreateError(message: WorktreeCreateCopy.unsupportedError)
    }
    func baseBranches(directory: String) async throws -> [String] { [] }
    var canCreateWorktrees: Bool { false }
}
