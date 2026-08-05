import SwiftUI

private let projectSessionSelectorPreviewSlotCount = 3

struct ProjectSessionSelectorSession: Identifiable, Hashable {
    let id: String
    let title: String
    let subtitle: String?

    init(id: String, title: String, subtitle: String? = nil) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
    }
}

struct ProjectSessionSelectorProject: Identifiable, Hashable {
    var id: String { worktree }

    let worktree: String
    let displayName: String
    let pathSubtitle: String
    let letter: Character
    let letterAccent: LetterAccent
    let previewSessions: [ProjectSessionSelectorSession]
    let totalSessionCount: Int
    let loadError: String?

    init(
        worktree: String,
        displayName: String,
        pathSubtitle: String,
        letter: Character,
        letterAccent: LetterAccent,
        previewSessions: [ProjectSessionSelectorSession],
        totalSessionCount: Int,
        loadError: String? = nil
    ) {
        self.worktree = worktree
        self.displayName = displayName
        self.pathSubtitle = pathSubtitle
        self.letter = letter
        self.letterAccent = letterAccent
        self.previewSessions = previewSessions
        self.totalSessionCount = totalSessionCount
        self.loadError = loadError
    }
}

struct ProjectSessionSelector: View {
    let projects: [ProjectSessionSelectorProject]
    let selectedWorktree: String?
    let createError: String?
    let onProjectSelected: (String) -> Void
    let onBrowseProjects: () -> Void
    let onSessionSelected: (ProjectSessionSelectorProject, ProjectSessionSelectorSession) -> Void
    let onShowAllSessions: (String) -> Void
    let onNewSession: (String) -> Void

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    private let horizontalSpacing: CGFloat = 12

    private var selectedProject: ProjectSessionSelectorProject? {
        if let selectedWorktree,
           let project = projects.first(where: { $0.worktree == selectedWorktree }) {
            return project
        }
        return projects.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            projectTiles

            if let selectedProject {
                projectHeader(selectedProject)
                Divider().overlay(OpenCodeColors.borderWeakerBase)
                sessionPreview(selectedProject)
                Divider().overlay(OpenCodeColors.borderWeakerBase)
                footer(selectedProject)
            }
        }
        .background(OpenCodeColors.surfaceBase, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(OpenCodeColors.borderWeakerBase, lineWidth: 1)
        )
    }

    private var projectTiles: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: horizontalSpacing) {
                ForEach(projects) { project in
                    ProjectSessionSelectorTile(
                        project: project,
                        selected: project.worktree == selectedProject?.worktree,
                        action: { onProjectSelected(project.worktree) }
                    )
                }
                AddProjectSessionSelectorTile(action: onBrowseProjects)
            }
            .padding(.horizontal, horizontalSpacing)
            .padding(.vertical, 20)
        }
    }

    private func projectHeader(_ project: ProjectSessionSelectorProject) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                OpenCodeIconImage(icon: .folder, size: 18)
                    .foregroundStyle(OpenCodeColors.iconBase)
                    .accessibilityHidden(true)
                Text(project.displayName)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.textStrong)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(compactHomePath(
                project.pathSubtitle,
                hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix
            ))
                .font(OpenCodeTypography.code)
                .foregroundStyle(OpenCodeColors.textBase)
                .lineLimit(1)
        }
        .padding(.horizontal, horizontalSpacing)
        .padding(.bottom, 16)
    }

    private func sessionPreview(_ project: ProjectSessionSelectorProject) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if let error = project.loadError {
                ZStack(alignment: .leading) {
                    placeholderSlots(count: 1)
                    Text(error)
                        .font(OpenCodeTypography.bodySecondary)
                        .foregroundStyle(OpenCodeColors.error)
                        .lineLimit(2)
                        .padding(.horizontal, horizontalSpacing)
                }
                placeholderSlots(count: projectSessionSelectorPreviewSlotCount - 1)
            } else if !project.previewSessions.isEmpty {
                let sessions = Array(project.previewSessions.prefix(projectSessionSelectorPreviewSlotCount))
                ForEach(sessions) { session in
                    OpenCodeListItem(
                        title: session.title,
                        subtitle: session.subtitle ?? "",
                        action: { onSessionSelected(project, session) }
                    )
                }
                placeholderSlots(count: projectSessionSelectorPreviewSlotCount - sessions.count)
            } else {
                placeholderSlots(count: projectSessionSelectorPreviewSlotCount)
            }
        }
    }

    private func placeholderSlots(count: Int) -> some View {
        ForEach(0 ..< max(count, 0), id: \.self) { _ in
            OpenCodeListItem(title: "Reserved session", subtitle: "Reserved session metadata", enabled: false)
                .hidden()
                .accessibilityHidden(true)
        }
    }

    private func footer(_ project: ProjectSessionSelectorProject) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .iconSquare(.list, accessibilityLabel: "Show all"),
                    action: { onShowAllSessions(project.worktree) }
                )
                OpenCodeButton(
                    variant: .fullWidthText(title: "New session"),
                    action: { onNewSession(project.worktree) }
                )
            }
            if let createError {
                Text(createError)
                    .font(OpenCodeTypography.bodySecondary)
                    .foregroundStyle(OpenCodeColors.error)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 12)
        .padding(.horizontal, horizontalSpacing)
        .padding(.bottom, 16)
    }
}

private struct ProjectSessionSelectorTile: View {
    let project: ProjectSessionSelectorProject
    let selected: Bool
    let action: () -> Void

    @State private var homeDisplayPreferences = HomeDisplayPreferences.shared

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Text(String(project.letter).uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(project.letterAccent.badgeTint)
                    .frame(width: 48, height: 48)
                    .background(project.letterAccent.badgeFill)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(selected ? project.letterAccent.badgeTint : Color.clear, lineWidth: 3)
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            "\(project.displayName), \(compactHomePath(project.pathSubtitle, hideUserDirectoryPrefix: homeDisplayPreferences.hideUserDirectoryPrefix))"
        )
        .accessibilityValue(selected ? "Selected" : "Not selected")
    }
}

private struct AddProjectSessionSelectorTile: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            OpenCodeIconImage(icon: .add, size: 22)
                .foregroundStyle(OpenCodeColors.textBase)
                .frame(width: 48, height: 48)
                .background(OpenCodeColors.surfaceRaisedBase, in: RoundedRectangle(cornerRadius: 12))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(
                            OpenCodeColors.borderWeakBase,
                            style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                        )
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Add project")
    }
}

private func compactHomePath(_ path: String, hideUserDirectoryPrefix: Bool) -> String {
    let home = NSHomeDirectory()
    if path == home { return "~" }
    if path.hasPrefix(home + "/") {
        return "~" + path.dropFirst(home.count)
    }
    guard hideUserDirectoryPrefix else { return path }
    if let remoteHome = remoteUnixHomePrefix(path) {
        if path == remoteHome { return "~" }
        if path.hasPrefix(remoteHome + "/") {
            return "~" + path.dropFirst(remoteHome.count)
        }
    }
    return path
}

/// `/Users/<name>` or `/home/<name>` prefix when the path is a remote server worktree.
private func remoteUnixHomePrefix(_ path: String) -> String? {
    let parts = path.split(separator: "/", maxSplits: 3, omittingEmptySubsequences: false)
    guard parts.count >= 3, parts[0].isEmpty else { return nil }
    let root = parts[1]
    guard root == "Users" || root == "home" else { return nil }
    let user = parts[2]
    guard !user.isEmpty else { return nil }
    return "/\(root)/\(user)"
}

#Preview("Project Session Selector") {
    ProjectSessionSelector(
        projects: projectSessionSelectorPreviewProjects(),
        selectedWorktree: "/Users/demo/opencode-mobile",
        createError: nil,
        onProjectSelected: { _ in },
        onBrowseProjects: {},
        onSessionSelected: { _, _ in },
        onShowAllSessions: { _ in },
        onNewSession: { _ in }
    )
    .padding(.vertical, 24)
    .padding(.horizontal, 16)
    .openCodeTheme()
}

#Preview("Project Session Selector Empty") {
    ProjectSessionSelector(
        projects: [
            ProjectSessionSelectorProject(
                worktree: "/Users/demo/empty",
                displayName: "empty",
                pathSubtitle: "/Users/demo/empty",
                letter: "E",
                letterAccent: .teal,
                previewSessions: [],
                totalSessionCount: 0
            ),
        ],
        selectedWorktree: "/Users/demo/empty",
        createError: nil,
        onProjectSelected: { _ in },
        onBrowseProjects: {},
        onSessionSelected: { _, _ in },
        onShowAllSessions: { _ in },
        onNewSession: { _ in }
    )
    .padding(.vertical, 24)
    .padding(.horizontal, 16)
    .openCodeTheme()
}

private func projectSessionSelectorPreviewProjects() -> [ProjectSessionSelectorProject] {
    let sessions = [
        ProjectSessionSelectorSession(id: "1", title: "Refactor Navigation", subtitle: "Oct 24 - 2:30 PM"),
        ProjectSessionSelectorSession(id: "2", title: "Bug: Header Clipping", subtitle: "Oct 23 - 11:45 AM"),
        ProjectSessionSelectorSession(id: "3", title: "Add Dark Mode Assets", subtitle: "Oct 22 - 4:15 PM"),
        ProjectSessionSelectorSession(id: "4", title: "Review Session List"),
    ]
    return [
        ProjectSessionSelectorProject(
            worktree: "/Users/demo/opencode-mobile",
            displayName: "opencode-mobile",
            pathSubtitle: "/Users/demo/opencode-mobile",
            letter: "O",
            letterAccent: .violet,
            previewSessions: Array(sessions.prefix(3)),
            totalSessionCount: sessions.count
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
}
