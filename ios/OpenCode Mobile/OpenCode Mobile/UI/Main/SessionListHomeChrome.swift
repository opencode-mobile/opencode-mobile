import Foundation

enum SessionListHomeChrome {
    enum NewSessionAction: Equatable {
        case none
        case presentPicker
        case create(directory: String)
        /// The project has more than one worktree: ask where to run.
        case chooseWorktree(projectKey: String)
    }

    enum TerminalAction: Equatable {
        case none
        case openCatalog
    }

    /// All (with two or more projects), then each project followed by its worktrees when it has
    /// more than one. Counts come from the deduplicated Home rows.
    static func filterTabs(
        projects: [HomeProject],
        sessions: [SessionListSession],
        loads: [String: SessionListScopeLoad] = [:]
    ) -> [SessionListFilterTab] {
        var projectCounts: [String: Int] = [:]
        var worktreeCounts: [String: Int] = [:]
        for session in sessions {
            projectCounts[session.worktree, default: 0] += 1
            if let scopeID = session.worktreeScopeID {
                worktreeCounts[scopeID, default: 0] += 1
            }
        }
        var tabs: [SessionListFilterTab] = []
        if projects.count >= 2 {
            tabs.append(.all(projectCount: projects.count, sessionCount: sessions.count))
        }
        for project in projects {
            tabs.append(
                .homeProject(project, count: projectCounts[project.key] ?? 0, load: loads[project.key])
            )
            guard project.hasManyWorktrees else { continue }
            for worktree in project.worktrees {
                let id = HomeScope.worktree(projectKey: project.key, directory: worktree.directory).id
                tabs.append(.homeWorktree(worktree, of: project, count: worktreeCounts[id] ?? 0))
            }
        }
        return tabs
    }

    /// Keeps the selection when it still exists, maps an added path or an old key onto the
    /// project that now holds it, else falls back to the first tab.
    static func resolvedFilterId(
        selectedId: String,
        tabs: [SessionListFilterTab],
        projects: [HomeProject] = []
    ) -> String {
        if tabs.contains(where: { $0.id == selectedId }) {
            return selectedId
        }
        if let mapped = HomeScope.resolve(id: selectedId, projects: projects)?.id,
           tabs.contains(where: { $0.id == mapped })
        {
            return mapped
        }
        return tabs.first?.id ?? SessionListFilterTab.allId
    }

    static func newSessionAction(projects: [HomeProject], selectedFilterId: String) -> NewSessionAction {
        if projects.isEmpty {
            return .none
        }
        switch HomeScope.resolve(id: selectedFilterId, projects: projects) {
        case let .worktree(_, directory):
            return .create(directory: directory)
        case let .project(key):
            if let project = projects.first(where: { $0.key == key }) {
                return pick(project)
            }
        case .all, nil:
            break
        }
        if projects.count == 1 {
            return pick(projects[0])
        }
        return .presentPicker
    }

    /// A project chosen from the New session picker.
    static func pick(_ project: HomeProject) -> NewSessionAction {
        project.hasManyWorktrees
            ? .chooseWorktree(projectKey: project.key)
            : .create(directory: project.primaryPath)
    }

    static func terminalAction(projects: [HomeProject]) -> TerminalAction {
        if projects.isEmpty {
            return .none
        }
        return .openCatalog
    }

    static func newSessionLongPressAction(projects: [HomeProject]) -> NewSessionAction {
        guard projects.count >= 2 else {
            return .none
        }
        return .presentPicker
    }

    /// One project's first-load state from its member paths: loading while any is loading,
    /// otherwise the first failure.
    static func projectLoad(
        _ project: HomeProject,
        loadStates: [String: ProjectLoadState]
    ) -> SessionListScopeLoad? {
        let loads = project.memberPaths.compactMap { loadStates[$0]?.scopeLoad }
        if loads.contains(where: \.isLoading) {
            let slow = loads.contains { if case .loading(true) = $0 { return true } else { return false } }
            return .loading(isSlow: slow)
        }
        return loads.first
    }
}

/// Run in rows for a multi-worktree project.
struct HomeRunInOption: Identifiable, Equatable {
    var id: String { directory }
    let directory: String
    let title: String
    let subtitle: String
    let runningCount: Int

    var trailing: String? {
        runningCount > 0 ? HomeProjectCopy.running(runningCount) : nil
    }

    static func options(for project: HomeProject, sessions: [SessionListSession]) -> [HomeRunInOption] {
        project.worktrees.map { worktree in
            let running = sessions.filter { session in
                guard session.worktree == project.key,
                      ServerPathPolicy.canonical(session.directory) == worktree.directory
                else { return false }
                switch session.status {
                case .running, .retrying: return true
                default: return false
                }
            }.count
            return HomeRunInOption(
                directory: worktree.directory,
                title: worktree.title,
                subtitle: worktree.subtitle,
                runningCount: running
            )
        }
    }
}

extension ProjectLoadState {
    var scopeLoad: SessionListScopeLoad {
        switch self {
        case let .loading(isSlow): .loading(isSlow: isSlow)
        case let .failed(message): .failed(message: message)
        }
    }
}
