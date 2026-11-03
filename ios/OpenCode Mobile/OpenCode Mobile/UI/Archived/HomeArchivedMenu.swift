import Foundation
import Observation

/// Home menu's "Archived sessions" entry: its scope and, once counted, how many are archived there.
struct HomeArchivedMenuEntry: Equatable {
    let scopeID: String
    let scopeTitle: String
    /// Nil while counting, or when the count failed.
    var count: Int?
}

/// Counts the archived sessions of Home's scope for the ⋯ menu entry. `entry` is nil on servers
/// that cannot list and restore archived sessions, so the menu leaves the entry out.
@MainActor
@Observable
final class HomeArchivedMenu {
    private(set) var entry: HomeArchivedMenuEntry?

    @ObservationIgnored private let sessionRepository: any SessionRepository
    @ObservationIgnored private let supported: @MainActor () -> Bool
    @ObservationIgnored private var task: Task<Void, Never>?

    init(sessionRepository: any SessionRepository, supported: @escaping @MainActor () -> Bool) {
        self.sessionRepository = sessionRepository
        self.supported = supported
    }

    /// Recounts for Home scope `scopeID`; Home calls it as the menu opens.
    func refresh(scopeID: String, projects: [HomeProject], worktreesEnabled: Bool) {
        task?.cancel()
        guard supported() else {
            entry = nil
            return
        }
        let resolved = HomeScope.resolve(id: scopeID, projects: projects, worktreesEnabled: worktreesEnabled) ?? .all
        let title = archivedScopeTitle(resolved, projects: projects)
        let previous = entry?.scopeID == scopeID ? entry?.count : nil
        entry = HomeArchivedMenuEntry(scopeID: scopeID, scopeTitle: title, count: previous)
        let repository = sessionRepository
        task = Task { [weak self] in
            let count = try? await Self.count(resolved, projects: projects, repository: repository)
            guard !Task.isCancelled, let self else { return }
            entry = HomeArchivedMenuEntry(scopeID: scopeID, scopeTitle: title, count: count)
        }
    }

    /// Waits for the count in flight (tests).
    func settle() async {
        await task?.value
    }

    private static func count(_ scope: HomeScope, projects: [HomeProject], repository: any SessionRepository) async throws -> Int {
        let inScope: [HomeProject] = switch scope {
        case .all: projects
        case let .project(key): projects.filter { $0.key == key }
        case let .worktree(projectKey, _): projects.filter { $0.key == projectKey }
        }
        var sessions: [Session] = []
        for project in inScope {
            sessions += try await ArchivedSessionList.load(repository: repository, directories: project.memberPaths)
        }
        let worktree: String? = if case let .worktree(_, directory) = scope { ServerPathPolicy.canonical(directory) } else { nil }
        return ArchivedSessionList.sorted(sessions).filter { session in
            guard let worktree else { return true }
            return session.directory.map(ServerPathPolicy.canonical) == worktree
        }.count
    }
}

/// The scope's name as the Archived sessions subtitle and the Home menu entry say it.
@MainActor
func archivedScopeTitle(_ scope: HomeScope, projects: [HomeProject]) -> String {
    switch scope {
    case .all:
        return String(localized: "home.all_projects", defaultValue: "All projects")
    case let .project(key):
        return projects.first { $0.key == key }?.displayName ?? ""
    case let .worktree(projectKey, directory):
        let project = projects.first { $0.key == projectKey }
        let worktree = project?.worktree(at: directory)
        return [project?.displayName, worktree?.folderName].compactMap { $0 }.joined(separator: " · ")
    }
}
