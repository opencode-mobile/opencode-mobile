import SwiftUI

struct ProjectSessionSelectorGalleryView: View {
    @State private var scenario: ProjectSessionSelectorGalleryScenario = .normal
    @State private var selectedWorktree = projectSessionSelectorGalleryProjects[0].worktree

    private var projects: [ProjectSessionSelectorProject] {
        scenario.projects
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Scenario")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            segmentedBadges(ProjectSessionSelectorGalleryScenario.allCases, selection: $scenario) { $0.label }

            Text("Selected project")
                .font(OpenCodeTypography.heading)
                .foregroundStyle(OpenCodeColors.textStrong)
            projectBadges

            ProjectSessionSelector(
                projects: projects,
                selectedWorktree: selectedWorktree,
                createError: scenario == .createError ? "Unable to create session" : nil,
                onProjectSelected: { selectedWorktree = $0 },
                onBrowseProjects: {},
                onSessionSelected: { _, _ in },
                onShowAllSessions: { _ in },
                onNewSession: { _ in }
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: scenario) { _, _ in
            if !projects.contains(where: { $0.worktree == selectedWorktree }) {
                selectedWorktree = projects.first?.worktree ?? ""
            }
        }
    }

    private var projectBadges: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(projects.chunked(into: 2), id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row) { project in
                        OpenCodeChoiceBadge(
                            label: project.displayName,
                            selected: selectedWorktree == project.worktree,
                            action: { selectedWorktree = project.worktree }
                        )
                    }
                }
            }
        }
    }

    private func segmentedBadges<Option: Hashable>(
        _ options: [Option],
        selection: Binding<Option>,
        label: @escaping (Option) -> String
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(options.chunked(into: 2), id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(row, id: \.self) { option in
                        OpenCodeChoiceBadge(
                            label: label(option),
                            selected: selection.wrappedValue == option,
                            action: { selection.wrappedValue = option }
                        )
                    }
                }
            }
        }
    }
}

private enum ProjectSessionSelectorGalleryScenario: CaseIterable, Hashable {
    case normal
    case empty
    case loadError
    case createError

    var label: String {
        switch self {
        case .normal:
            "Normal"
        case .empty:
            "Empty"
        case .loadError:
            "Load error"
        case .createError:
            "Create error"
        }
    }

    var projects: [ProjectSessionSelectorProject] {
        switch self {
        case .normal, .createError:
            projectSessionSelectorGalleryProjects
        case .empty:
            [projectSessionSelectorGalleryEmptyProject]
        case .loadError:
            [projectSessionSelectorGalleryLoadErrorProject]
        }
    }
}

private let projectSessionSelectorGallerySessions = [
    ProjectSessionSelectorSession(id: "session-1", title: "Refactor Navigation", subtitle: "Oct 24 - 2:30 PM"),
    ProjectSessionSelectorSession(id: "session-2", title: "Bug: Header Clipping", subtitle: "Oct 23 - 11:45 AM"),
    ProjectSessionSelectorSession(id: "session-3", title: "Add Dark Mode Assets", subtitle: "Oct 22 - 4:15 PM"),
    ProjectSessionSelectorSession(id: "session-4", title: "Review Session List", subtitle: "Idle"),
]

private let projectSessionSelectorGalleryProjects = [
    ProjectSessionSelectorProject(
        worktree: "/Users/demo/opencode-mobile",
        displayName: "opencode-mobile",
        pathSubtitle: "/Users/demo/opencode-mobile",
        letter: "O",
        letterAccent: .violet,
        previewSessions: Array(projectSessionSelectorGallerySessions.prefix(3)),
        totalSessionCount: projectSessionSelectorGallerySessions.count
    ),
    ProjectSessionSelectorProject(
        worktree: "/Users/demo/playground",
        displayName: "playground",
        pathSubtitle: "/Users/demo/playground",
        letter: "P",
        letterAccent: .amber,
        previewSessions: [],
        totalSessionCount: 0
    ),
]

private let projectSessionSelectorGalleryEmptyProject =
    ProjectSessionSelectorProject(
        worktree: "/Users/demo/empty",
        displayName: "empty",
        pathSubtitle: "/Users/demo/empty",
        letter: "E",
        letterAccent: .teal,
        previewSessions: [],
        totalSessionCount: 0
    )

private let projectSessionSelectorGalleryLoadErrorProject =
    ProjectSessionSelectorProject(
        worktree: "/Users/demo/api-client",
        displayName: "api-client",
        pathSubtitle: "/Users/demo/api-client",
        letter: "A",
        letterAccent: .rose,
        previewSessions: [],
        totalSessionCount: 0,
        loadError: "Unable to load sessions"
    )

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map {
            Array(self[$0 ..< Swift.min($0 + size, count)])
        }
    }
}

#Preview {
    ProjectSessionSelectorGalleryView()
        .padding()
        .openCodeTheme()
}
