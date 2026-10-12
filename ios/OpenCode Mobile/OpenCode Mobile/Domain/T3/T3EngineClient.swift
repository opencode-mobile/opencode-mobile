import Foundation

/// The slice of `T3EnvironmentClient` that the T3 repositories depend on.
/// Keeps the repositories testable with an in-memory shell instead of a
/// live T3 environment.
protocol T3EngineClient: Sendable {
    func fetchShell(force: Bool) async throws -> T3ShellSnapshot
    func cachedThread(id: String) -> T3Thread?
    func fetchThread(id: String) async throws -> T3ThreadDetail
    func dispatchCommand(_ command: [String: Any]) async throws
    /// Launches a thread in `projectID`, or in the environment's first project
    /// when nil. A nil `selection` is resolved from the harness catalogue
    /// (`T3ModelSelectionResolver`); a nil `runtimeMode` means approval-required.
    func launchThread(
        title: String?,
        projectID: String?,
        selection: T3ModelSelection?,
        runtimeMode: String?
    ) async throws -> Session
    /// Provider catalogue (`server.getConfig`), cached per connection unless `force`.
    func fetchHarnessCatalog(force: Bool) async throws -> T3HarnessCatalog
    /// Opens the environment socket in the background so the connection
    /// indicator shows connecting / connected before the first request.
    func warmConnection()
    /// Moves `id` into the archive list. The archive command is accepted before
    /// the shell stream catches up, and the active HTTP snapshot does not carry
    /// archived threads. A no-op when the cache has no such thread.
    func noteThreadArchived(id: String)
    /// Moves `id` back to the active list and clears `archivedAt`.
    func noteThreadUnarchived(id: String)
}

extension T3EngineClient {
    func launchThread(title: String?, projectID: String?) async throws -> Session {
        try await launchThread(title: title, projectID: projectID, selection: nil, runtimeMode: nil)
    }

    func fetchHarnessCatalog(force _: Bool) async throws -> T3HarnessCatalog {
        throw T3ClientError.notConnected
    }

    func warmConnection() {}

    func noteThreadArchived(id _: String) {}

    func noteThreadUnarchived(id _: String) {}
}

extension T3EnvironmentClient: T3EngineClient {}

enum T3ProjectSelection {
    /// Picks the project that owns `directory`: its workspace root or a thread
    /// worktree under it. Falls back to the first project like Android.
    static func project(for directory: String?, in snapshot: T3ShellSnapshot) -> T3Project? {
        guard let directory, !directory.isEmpty else { return snapshot.projects.first }
        return snapshot.projects.first { project in
            project.workspaceRoot == directory
                || snapshot.threads.contains { $0.projectID == project.id && $0.worktreePath == directory }
        } ?? snapshot.projects.first
    }

    static func matches(_ thread: T3Thread, directory: String?, in snapshot: T3ShellSnapshot) -> Bool {
        guard let directory, !directory.isEmpty else { return true }
        if thread.worktreePath == directory { return true }
        let root = snapshot.projects.first { $0.id == thread.projectID }?.workspaceRoot
        return root == directory
    }
}
