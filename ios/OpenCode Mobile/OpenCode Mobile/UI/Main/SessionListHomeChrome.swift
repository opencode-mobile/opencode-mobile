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

    static func newSessionAction(projects: [Project]) -> NewSessionAction {
        switch projects.count {
        case 0:
            .none
        case 1:
            .create(worktree: projects[0].worktree)
        default:
            .presentPicker
        }
    }
}
