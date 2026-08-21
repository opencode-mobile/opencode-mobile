import Foundation

enum SessionListHomeChrome {
    struct Project: Equatable {
        let worktree: String
        let title: String
        let sessionCount: Int
    }

    enum NewSessionAction: Equatable {
        case none
        case presentPicker
        case create(worktree: String)
    }

    static func filterTabs(projects: [Project], sessionCount: Int) -> [SessionListFilterTab] {
        let projectTabs = projects.map { project in
            SessionListFilterTab.project(
                worktree: project.worktree,
                title: project.title,
                count: project.sessionCount
            )
        }
        guard projects.count >= 2 else {
            return projectTabs
        }
        return [
            SessionListFilterTab.all(projectCount: projects.count, sessionCount: sessionCount),
        ] + projectTabs
    }

    static func resolvedFilterId(selectedId: String, tabs: [SessionListFilterTab]) -> String {
        if tabs.contains(where: { $0.id == selectedId }) {
            return selectedId
        }
        return tabs.first?.id ?? SessionListFilterTab.allId
    }

    static func newSessionAction(projects: [Project], selectedFilterId: String) -> NewSessionAction {
        if projects.isEmpty {
            return .none
        }
        if selectedFilterId != SessionListFilterTab.allId,
           projects.contains(where: { $0.worktree == selectedFilterId }) {
            return .create(worktree: selectedFilterId)
        }
        if projects.count == 1 {
            return .create(worktree: projects[0].worktree)
        }
        return .presentPicker
    }

    static func newSessionLongPressAction(
        projects: [Project],
        useProjectPicker: Bool
    ) -> NewSessionAction {
        guard useProjectPicker, projects.count >= 2 else {
            return .none
        }
        return .presentPicker
    }
}
