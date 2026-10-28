import Foundation

/// Expand / show-all / edit state of the Home project picker. Reset each time the sheet opens.
struct HomeProjectPickerState: Equatable {
    /// Worktree rows shown under an open project before "Show all N".
    static let collapsedWorktreeLimit = 5

    /// The one project whose worktrees are open.
    private(set) var expandedProjectKey: String?
    private(set) var showAllProjectKeys: Set<String> = []
    private(set) var isEditing = false

    init(expandedProjectKey: String? = nil, showAllProjectKeys: Set<String> = [], isEditing: Bool = false) {
        self.expandedProjectKey = expandedProjectKey
        self.showAllProjectKeys = showAllProjectKeys
        self.isEditing = isEditing
    }

    /// Opening on a worktree scope expands its project, with every worktree shown when the
    /// selected one is past the first five. Anything else starts collapsed.
    static func initial(
        selectedScopeId: String,
        projects: [HomeProject],
        worktreesEnabled: Bool = true
    ) -> HomeProjectPickerState {
        guard worktreesEnabled,
              case let .worktree(projectKey, directory)? = HomeScope.resolve(id: selectedScopeId, projects: projects),
              let project = projects.first(where: { $0.key == projectKey })
        else { return HomeProjectPickerState() }
        let index = project.worktrees.firstIndex { $0.directory == directory } ?? 0
        return HomeProjectPickerState(
            expandedProjectKey: projectKey,
            showAllProjectKeys: index >= collapsedWorktreeLimit ? [projectKey] : []
        )
    }

    func isExpanded(_ projectKey: String) -> Bool {
        expandedProjectKey == projectKey
    }

    func showsAll(_ projectKey: String) -> Bool {
        showAllProjectKeys.contains(projectKey)
    }

    /// Opens `projectKey` and closes any other; the open one closes. Inert while editing.
    mutating func toggleExpanded(_ projectKey: String) {
        guard !isEditing else { return }
        expandedProjectKey = expandedProjectKey == projectKey ? nil : projectKey
    }

    mutating func toggleShowAll(_ projectKey: String) {
        if showAllProjectKeys.contains(projectKey) {
            showAllProjectKeys.remove(projectKey)
        } else {
            showAllProjectKeys.insert(projectKey)
        }
    }

    /// Entering and leaving Edit both leave everything collapsed.
    mutating func setEditing(_ editing: Bool) {
        isEditing = editing
        expandedProjectKey = nil
        showAllProjectKeys = []
    }
}

/// The worktree count pill on a multi-worktree project row.
struct HomeProjectPickerPill: Hashable {
    let title: String
    let isExpanded: Bool
    /// Visible but inert while editing.
    let enabled: Bool
    let accessibilityLabel: String
}

/// One row of the project picker.
enum HomeProjectPickerRow: Identifiable, Hashable {
    /// "Drag to reorder…" while editing.
    case note
    case all(SessionListFilterTab)
    case project(SessionListFilterTab, pill: HomeProjectPickerPill?)
    case worktree(SessionListFilterTab)
    /// "Show all N" / "Show fewer" under an open project with more than five worktrees.
    case showAll(projectKey: String, title: String)

    static let noteID = "note"
    static let showAllPrefix = "showAll:"

    var id: String {
        switch self {
        case .note: Self.noteID
        case let .all(tab), let .project(tab, _), let .worktree(tab): tab.id
        case let .showAll(projectKey, _): Self.showAllPrefix + projectKey
        }
    }

    var isProject: Bool {
        if case .project = self { return true }
        return false
    }

    var tab: SessionListFilterTab? {
        switch self {
        case let .all(tab), let .project(tab, _), let .worktree(tab): tab
        case .note, .showAll: nil
        }
    }

    /// Picker rows: an optional edit note, All, then each project with its open worktrees.
    static func rows(
        tabs: [SessionListFilterTab],
        projects: [HomeProject],
        sessions: [SessionListSession],
        state: HomeProjectPickerState,
        worktreesEnabled: Bool = true
    ) -> [HomeProjectPickerRow] {
        var rows: [HomeProjectPickerRow] = state.isEditing ? [.note] : []
        let projectsByKey = Dictionary(projects.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        for tab in tabs {
            if tab.isAll {
                rows.append(.all(tab))
                continue
            }
            guard let project = projectsByKey[tab.id], worktreesEnabled, project.hasManyWorktrees else {
                rows.append(.project(tab, pill: nil))
                continue
            }
            let expanded = !state.isEditing && state.isExpanded(project.key)
            rows.append(
                .project(
                    tab,
                    pill: HomeProjectPickerPill(
                        title: HomeProjectCopy.worktreeCount(project.worktrees.count),
                        isExpanded: expanded,
                        enabled: !state.isEditing,
                        accessibilityLabel: expanded
                            ? HomeProjectCopy.hideWorktrees(of: project.displayName)
                            : HomeProjectCopy.showWorktrees(of: project.displayName)
                    )
                )
            )
            guard expanded else { continue }
            let worktrees = SessionListHomeChrome.worktreeTabs(for: project, sessions: sessions)
            let limit = HomeProjectPickerState.collapsedWorktreeLimit
            let showsAll = state.showsAll(project.key)
            let visible = worktrees.count <= limit || showsAll ? worktrees : Array(worktrees.prefix(limit))
            rows.append(contentsOf: visible.map(HomeProjectPickerRow.worktree))
            if worktrees.count > limit {
                rows.append(
                    .showAll(
                        projectKey: project.key,
                        title: showsAll ? HomeProjectCopy.showFewer : HomeProjectCopy.showAll(worktrees.count)
                    )
                )
            }
        }
        return rows
    }

    /// Project keys in their new order after moving row `from` to `to`.
    static func reorderedProjectKeys(_ rows: [HomeProjectPickerRow], from: Int, to: Int) -> [String] {
        OpenCodeReorderableListMath.move(rows, from: from, to: to)
            .filter(\.isProject)
            .map(\.id)
    }
}
