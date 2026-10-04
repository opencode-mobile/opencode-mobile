import Foundation

/// The slice of `T3EnvironmentClient` that the T3 repositories depend on.
/// Keeps the repositories testable with an in-memory shell instead of a
/// live T3 environment.
protocol T3EngineClient: Sendable {
    func fetchShell(force: Bool) async throws -> T3ShellSnapshot
    func cachedThread(id: String) -> T3Thread?
    func fetchThread(id: String) async throws -> T3ThreadDetail
    func dispatchCommand(_ command: [String: Any]) async throws
    /// Launches a thread in `projectID`, or in the environment's first project when nil.
    func launchThread(title: String?, projectID: String?) async throws -> Session
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
