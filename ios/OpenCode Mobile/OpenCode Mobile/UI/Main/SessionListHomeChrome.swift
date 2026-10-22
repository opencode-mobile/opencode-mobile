import Foundation

enum SessionListHomeChrome {
    enum NewSessionAction: Equatable {
        case none
        /// All scope with two or more projects: choose the project (tap).
        case presentPicker
        case create(directory: String)
    }

    /// What the New session sheet lists.
    enum NewSessionSheetMode: Equatable {
        /// The projects. `holdPick` (opened by a hold) lets a multi-worktree pick open its Run in list.
        case projects(holdPick: Bool)
        /// Where to run in one project, then the other projects when `showsOtherProjects`.
        case runIn(projectKey: String, showsOtherProjects: Bool)

        /// The sheet came from a hold, so a project pick may still ask where to run.
        var isHoldPick: Bool {
            switch self {
            case let .projects(holdPick): holdPick
            case .runIn: true
            }
        }
    }

    /// What picking a project in the New session sheet does.
    enum NewSessionPick: Equatable {
        case create(directory: String)
        case show(NewSessionSheetMode)
    }

    enum TerminalAction: Equatable {
        case none
        case openCatalog
    }

    /// All (with two or more projects), then each project. Worktrees are reached from the
    /// picker (`worktreeTabs`), never listed here. Counts come from the deduplicated Home rows.
    static func filterTabs(
        projects: [HomeProject],
        sessions: [SessionListSession],
        loads: [String: SessionListScopeLoad] = [:],
        worktreesEnabled: Bool = true
    ) -> [SessionListFilterTab] {
        var projectCounts: [String: Int] = [:]
        for session in sessions {
            projectCounts[session.worktree, default: 0] += 1
        }
        var tabs: [SessionListFilterTab] = []
        if projects.count >= 2 {
            tabs.append(.all(projectCount: projects.count, sessionCount: sessions.count))
        }
        for project in projects {
            tabs.append(
                .homeProject(
                    project,
                    count: projectCounts[project.key] ?? 0,
                    load: loads[project.key],
                    worktreesEnabled: worktreesEnabled
                )
            )
        }
        return tabs
    }

    /// The worktree scopes of a multi-worktree project, main checkout first. Empty for a project
    /// with one worktree.
    static func worktreeTabs(for project: HomeProject, sessions: [SessionListSession]) -> [SessionListFilterTab] {
        guard project.hasManyWorktrees else { return [] }
        var counts: [String: Int] = [:]
        for session in sessions {
            if let scopeID = session.worktreeScopeID {
                counts[scopeID, default: 0] += 1
            }
        }
        return project.worktrees.map { worktree in
            let id = HomeScope.worktree(projectKey: project.key, directory: worktree.directory).id
            return .homeWorktree(worktree, of: project, count: counts[id] ?? 0)
        }
    }

    /// Dock tab of a worktree scope selection, which `filterTabs` does not list. Nil otherwise.
    static func selectedScopeTab(
        selectedId: String,
        tabs: [SessionListFilterTab],
        projects: [HomeProject],
        sessions: [SessionListSession],
        worktreesEnabled: Bool = true
    ) -> SessionListFilterTab? {
        guard worktreesEnabled, !tabs.contains(where: { $0.id == selectedId }),
              case let .worktree(projectKey, _) = HomeScope.resolve(id: selectedId, projects: projects),
              let project = projects.first(where: { $0.key == projectKey })
        else { return nil }
        return worktreeTabs(for: project, sessions: sessions).first { $0.id == selectedId }
    }

    /// Keeps the selection when it still exists (a worktree scope too, while worktrees are on),
    /// maps an added path, an old key or (worktrees off) a worktree scope onto the project that
    /// now holds it, else falls back to the first tab.
    static func resolvedFilterId(
        selectedId: String,
        tabs: [SessionListFilterTab],
        projects: [HomeProject] = [],
        worktreesEnabled: Bool = true
    ) -> String {
        if tabs.contains(where: { $0.id == selectedId }) {
            return selectedId
        }
        if let resolved = HomeScope.resolve(id: selectedId, projects: projects, worktreesEnabled: worktreesEnabled) {
            // A worktree scope is valid while its project is listed.
            let listedID = resolved.projectKey.map { HomeScope.project($0).id } ?? resolved.id
            if tabs.contains(where: { $0.id == listedID }) {
                return resolved.id
            }
        }
        return tabs.first?.id ?? SessionListFilterTab.allId
    }

    /// The project New session starts in: the scope's project (a worktree scope's owner), or the
    /// only project in the All scope. Nil in the All scope with several projects.
    static func targetProject(
        projects: [HomeProject],
        selectedFilterId: String,
        worktreesEnabled: Bool = true
    ) -> HomeProject? {
        let scope = HomeScope.resolve(id: selectedFilterId, projects: projects, worktreesEnabled: worktreesEnabled)
        if let key = scope?.projectKey, let project = projects.first(where: { $0.key == key }) {
            return project
        }
        return projects.count == 1 ? projects[0] : nil
    }

    /// Tap: start on the main checkout of the current project, or in the filtered worktree.
    /// Nothing is asked except which project in the All scope.
    static func newSessionAction(
        projects: [HomeProject],
        selectedFilterId: String,
        worktreesEnabled: Bool = true
    ) -> NewSessionAction {
        if projects.isEmpty {
            return .none
        }
        if worktreesEnabled,
           case let .worktree(_, directory)? = HomeScope.resolve(id: selectedFilterId, projects: projects)
        {
            return .create(directory: directory)
        }
        if let project = targetProject(
            projects: projects,
            selectedFilterId: selectedFilterId,
            worktreesEnabled: worktreesEnabled
        ) {
            return .create(directory: project.mainCheckoutDirectory)
        }
        return .presentPicker
    }

    /// Hold: Run in for a multi-worktree target project (worktrees on), else the project list
    /// with two or more projects, else nothing to choose (no hold action).
    static func newSessionLongPressAction(
        projects: [HomeProject],
        selectedFilterId: String,
        worktreesEnabled: Bool = true
    ) -> NewSessionSheetMode? {
        if worktreesEnabled,
           let target = targetProject(projects: projects, selectedFilterId: selectedFilterId),
           target.hasManyWorktrees
        {
            return .runIn(projectKey: target.key, showsOtherProjects: projects.count >= 2)
        }
        if projects.count >= 2 {
            return .projects(holdPick: true)
        }
        return nil
    }

    /// A project chosen in the New session sheet. After a tap it starts on its main checkout;
    /// after a hold a multi-worktree project (worktrees on) opens its Run in list instead.
    static func pick(
        _ project: HomeProject,
        holdPick: Bool,
        worktreesEnabled: Bool = true
    ) -> NewSessionPick {
        if holdPick, worktreesEnabled, project.hasManyWorktrees {
            return .show(.runIn(projectKey: project.key, showsOtherProjects: false))
        }
        return .create(directory: project.mainCheckoutDirectory)
    }

    /// Projects listed under "Other projects" below a Run in list.
    static func otherProjects(
        _ projects: [HomeProject],
        mode: NewSessionSheetMode
    ) -> [HomeProject] {
        guard case let .runIn(projectKey, true) = mode else { return [] }
        return projects.filter { $0.key != projectKey }
    }

    static func terminalAction(projects: [HomeProject]) -> TerminalAction {
        if projects.isEmpty {
            return .none
        }
        return .openCatalog
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
