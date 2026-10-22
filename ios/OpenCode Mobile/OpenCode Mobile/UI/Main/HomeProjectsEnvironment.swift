import SwiftUI

private struct HomeProjectsKey: EnvironmentKey {
    static let defaultValue: [HomeProject] = []
}

private struct RefreshHomeWorktreesKey: EnvironmentKey {
    static let defaultValue: @MainActor @Sendable (String) async -> Void = { _ in }
}

extension EnvironmentValues {
    /// Reloads the checkouts of the Home project that owns a directory (Session Chat after it
    /// created a worktree).
    var refreshHomeWorktrees: @MainActor @Sendable (String) async -> Void {
        get { self[RefreshHomeWorktreesKey.self] }
        set { self[RefreshHomeWorktreesKey.self] = newValue }
    }

    /// Home's projects with their worktrees, for screens opened from Home (Session Chat
    /// "Move to worktree…" lists the session's project worktrees in Home order).
    var homeProjects: [HomeProject] {
        get { self[HomeProjectsKey.self] }
        set { self[HomeProjectsKey.self] = newValue }
    }
}
