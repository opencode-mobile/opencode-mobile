import Foundation
import Observation

/// A Home session as the Worktrees screen sees it.
nonisolated struct HomeWorktreeSession: Equatable, Sendable {
    let id: String
    let title: String
    /// Canonical directory the session runs in.
    let directory: String
    /// Running or retrying.
    let isRunning: Bool
}

/// Home's projects and sessions, published for the Worktrees screen.
nonisolated struct HomeWorktreesSnapshot: Equatable, Sendable {
    var projects: [HomeProject] = []
    var sessions: [HomeWorktreeSession] = []

    func sessions(in directory: String) -> [HomeWorktreeSession] {
        let canonical = ServerPathPolicy.canonical(directory.trimmingCharacters(in: .whitespacesAndNewlines))
        return sessions.filter { $0.directory == canonical }
    }

    /// Worktrees other than main checkouts, across every project.
    var worktreeCount: Int {
        projects.reduce(0) { $0 + $1.worktrees.filter { !$0.isMain }.count }
    }

    var projectsWithWorktrees: Int {
        projects.filter { $0.worktrees.contains { !$0.isMain } }.count
    }
}

/// What the Worktrees screen asks Home to do.
nonisolated enum HomeWorktreeRequest: Equatable, Sendable {
    /// Switch Home's scope picker to the worktree.
    case showSessions(projectKey: String, directory: String)
    /// Start a new session in the worktree.
    case newSession(directory: String)
    /// The server removed the worktree: list the project's worktrees again.
    case removed(projectKey: String, directory: String)
}

/// Connects Home and the Worktrees screen, which is its own route: Home publishes its projects
/// and sessions, the Worktrees screen sends requests back. Worktrees removed from this app are
/// kept, per connection, so their sessions stay on Home marked as removed.
@MainActor
@Observable
final class HomeWorktreesFeed {
    private(set) var snapshot = HomeWorktreesSnapshot()
    /// Removed worktrees: canonical directory to project key.
    private(set) var removed: [String: String] = [:]
    /// Home's handler; set once Home exists.
    @ObservationIgnored var onRequest: ((HomeWorktreeRequest) -> Void)?

    var removedDirectories: Set<String> { Set(removed.keys) }

    func publish(_ snapshot: HomeWorktreesSnapshot) {
        guard self.snapshot != snapshot else { return }
        self.snapshot = snapshot
    }

    func send(_ request: HomeWorktreeRequest) {
        if case let .removed(projectKey, directory) = request {
            removed[ServerPathPolicy.canonical(directory.trimmingCharacters(in: .whitespacesAndNewlines))] = projectKey
        }
        onRequest?(request)
    }

    /// Another server: nothing of the last one applies.
    func clear() {
        snapshot = HomeWorktreesSnapshot()
        removed.removeAll()
    }
}
