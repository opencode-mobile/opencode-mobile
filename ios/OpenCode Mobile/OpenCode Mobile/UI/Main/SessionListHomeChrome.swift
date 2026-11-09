import Foundation

enum SessionListHomeChrome {
    enum TerminalAction: Equatable {
        case none
        case openCatalog
    }

    /// All (with two or more projects), then each project. Worktrees are reached from the
    /// picker (`worktreeTabs`), never listed here. No session counts: Home loads a window of
    /// each project's newest sessions, not all of them.
    static func filterTabs(
        projects: [HomeProject],
        loads: [String: SessionListScopeLoad] = [:],
        worktreesEnabled: Bool = true
    ) -> [SessionListFilterTab] {
        var tabs: [SessionListFilterTab] = []
        if projects.count >= 2 {
            let loading = projects.compactMap { project -> Bool? in
                if case let .loading(isSlow) = loads[project.key] { return isSlow }
                return nil
            }
            let projectCount = projects.count
            tabs.append(
                SessionListFilterTab(
                    id: SessionListFilterTab.allId,
                    title: String(localized: "home.all", defaultValue: "All"),
                    subtitle: String(localized: LocalizedStringResource(
                        "home.project_count",
                        defaultValue: "\(projectCount) projects"
                    )),
                    load: loading.isEmpty ? nil : .loading(isSlow: loading.contains(true)),
                    pickerTitle: String(localized: "home.all_projects", defaultValue: "All projects")
                )
            )
        }
        for project in projects {
            tabs.append(
                .homeProject(
                    project,
                    load: loads[project.key],
                    worktreesEnabled: worktreesEnabled
                )
            )
        }
        return tabs
    }

    /// The worktree scopes of a multi-worktree project, main checkout first. Empty for a project
    /// with one worktree.
    static func worktreeTabs(for project: HomeProject) -> [SessionListFilterTab] {
        guard project.hasManyWorktrees else { return [] }
        return project.worktrees.map { worktree in
            .homeWorktree(worktree, of: project)
        }
    }

    /// Dock tab of a worktree scope selection, which `filterTabs` does not list. Nil otherwise.
    static func selectedScopeTab(
        selectedId: String,
        tabs: [SessionListFilterTab],
        projects: [HomeProject],
        worktreesEnabled: Bool = true
    ) -> SessionListFilterTab? {
        guard worktreesEnabled, !tabs.contains(where: { $0.id == selectedId }),
              case let .worktree(projectKey, _) = HomeScope.resolve(id: selectedId, projects: projects),
              let project = projects.first(where: { $0.key == projectKey })
        else { return nil }
        return worktreeTabs(for: project).first { $0.id == selectedId }
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

extension ProjectLoadState {
    var scopeLoad: SessionListScopeLoad {
        switch self {
        case let .loading(isSlow): .loading(isSlow: isSlow)
        case let .failed(message): .failed(message: message)
        }
    }
}
