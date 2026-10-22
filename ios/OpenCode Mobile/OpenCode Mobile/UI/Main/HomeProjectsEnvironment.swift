import SwiftUI

private struct HomeProjectsKey: EnvironmentKey {
    static let defaultValue: [HomeProject] = []
}

extension EnvironmentValues {
    /// Home's projects with their worktrees, for screens opened from Home (Session Chat
    /// "Move to worktree…" lists the session's project worktrees in Home order).
    var homeProjects: [HomeProject] {
        get { self[HomeProjectsKey.self] }
        set { self[HomeProjectsKey.self] = newValue }
    }
}
