import Foundation
import Observation

/// The same folder however the server spells it: macOS links `/tmp`, `/var` and `/etc` into
/// `/private`, and a server can report either form for one worktree.
nonisolated func worktreePathKey(_ directory: String) -> String {
    let canonical = ServerPathPolicy.canonical(directory.trimmingCharacters(in: .whitespacesAndNewlines))
    for link in ["/tmp", "/var", "/etc"] where canonical == "/private" + link || canonical.hasPrefix("/private" + link + "/") {
        return String(canonical.dropFirst("/private".count))
    }
    return canonical
}

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
    /// Canonical directories the server listed as worktrees, never a main checkout. Only these can
    /// be removed; other non-main entries of a project (an added path the server does not list)
    /// are not worktrees the server knows.
    var removableDirectories: Set<String> = []
    /// Home has listed worktrees at least once; until then the counts mean nothing.
    var worktreesLoaded = true

    /// A project's worktrees the server can remove, one per folder: the server's spelling wins when
    /// Home knows the folder under two paths. Never the main checkout, under either spelling.
    func removableWorktrees(_ project: HomeProject) -> [HomeWorktree] {
        let removableKeys = Set(removableDirectories.map(worktreePathKey))
        let mains = Set(project.worktrees.filter(\.isMain).map { worktreePathKey($0.directory) })
        var order: [String] = []
        var byKey: [String: [HomeWorktree]] = [:]
        for worktree in project.worktrees where !worktree.isMain {
            let key = worktreePathKey(worktree.directory)
            guard removableKeys.contains(key), !mains.contains(key) else { continue }
            if byKey[key] == nil { order.append(key) }
            byKey[key, default: []].append(worktree)
        }
        return order.compactMap { key in
            let same = byKey[key] ?? []
            return same.first { removableDirectories.contains($0.directory) } ?? same.first
        }
    }

    /// Sessions in `directory`, under either spelling of the folder.
    func sessions(in directory: String) -> [HomeWorktreeSession] {
        let key = worktreePathKey(directory)
        return sessions.filter { worktreePathKey($0.directory) == key }
    }

    /// Removable worktrees, across every project.
    var worktreeCount: Int {
        projects.reduce(0) { $0 + removableWorktrees($1).count }
    }

    var projectsWithWorktrees: Int {
        projects.filter { !removableWorktrees($0).isEmpty }.count
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
