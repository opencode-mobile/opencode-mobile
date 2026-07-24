import SwiftUI

enum SessionListStatus: Hashable {
    case idle
    case running
}

struct SessionListSession: Identifiable, Hashable {
    let id: String
    let title: String
    let worktree: String
    let letter: Character
    let letterAccent: LetterAccent
    let subtitle: String
    let status: SessionListStatus
    let caption: String

    init(
        id: String,
        title: String,
        worktree: String,
        letter: Character,
        letterAccent: LetterAccent,
        subtitle: String,
        status: SessionListStatus,
        caption: String
    ) {
        self.id = id
        self.title = title
        self.worktree = worktree
        self.letter = letter
        self.letterAccent = letterAccent
        self.subtitle = subtitle
        self.status = status
        self.caption = caption
    }
}

struct SessionListFilterTab: Identifiable, Hashable {
    static let allId = "all"

    let id: String
    let title: String
    let count: Int

    init(id: String, title: String, count: Int) {
        self.id = id
        self.title = title
        self.count = count
    }

    var filterTab: OpenCodeFilterTab {
        OpenCodeFilterTab(id: id, title: title, count: count)
    }
}

struct SessionList: View {
    let sessions: [SessionListSession]
    let filterTabs: [SessionListFilterTab]
    let selectedFilterId: String
    let createError: String?
    let onFilterSelected: (String) -> Void
    let onSessionSelected: (SessionListSession) -> Void
    let onAddProject: () -> Void
    let onNewSession: () -> Void

    init(
        sessions: [SessionListSession],
        filterTabs: [SessionListFilterTab],
        selectedFilterId: String,
        createError: String? = nil,
        onFilterSelected: @escaping (String) -> Void,
        onSessionSelected: @escaping (SessionListSession) -> Void,
        onAddProject: @escaping () -> Void,
        onNewSession: @escaping () -> Void
    ) {
        self.sessions = sessions
        self.filterTabs = filterTabs
        self.selectedFilterId = selectedFilterId
        self.createError = createError
        self.onFilterSelected = onFilterSelected
        self.onSessionSelected = onSessionSelected
        self.onAddProject = onAddProject
        self.onNewSession = onNewSession
    }

    private var visibleSessions: [SessionListSession] {
        if selectedFilterId == SessionListFilterTab.allId {
            return sessions
        }
        return sessions.filter { $0.worktree == selectedFilterId }
    }

    var body: some View {
        VStack(spacing: 0) {
            OpenCodeFilterTabs(
                tabs: filterTabs.map(\.filterTab),
                selectedTabId: selectedFilterId,
                onTabSelected: onFilterSelected
            )

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(visibleSessions) { session in
                        OpenCodeListItem(
                            title: session.title,
                            subtitle: compactHomePath(session.subtitle),
                            status: listItemStatus(for: session.status),
                            caption: session.caption.isEmpty ? nil : session.caption,
                            style: .plain,
                            startDecorator: .letter(session.letter, session.letterAccent),
                            action: { onSessionSelected(session) }
                        )
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            footer
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var footer: some View {
        VStack(spacing: 16) {
            HStack(spacing: 8) {
                OpenCodeButton(
                    variant: .iconSquare(.add, accessibilityLabel: "Add project"),
                    action: onAddProject
                )
                OpenCodeButton(
                    variant: .fullWidthText(title: "New session"),
                    action: onNewSession
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
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }

    private func listItemStatus(for status: SessionListStatus) -> OpenCodeListItemStatus {
        switch status {
        case .idle:
            OpenCodeListItemStatus(text: "Idle", tone: .info)
        case .running:
            OpenCodeListItemStatus(text: "Running", tone: .interactive)
        }
    }
}

private func compactHomePath(_ path: String) -> String {
    let home = NSHomeDirectory()
    if path == home { return "~" }
    if path.hasPrefix(home + "/") {
        return "~" + path.dropFirst(home.count)
    }
    return path
}

#if DEBUG
#Preview {
    SessionList(
        sessions: [
            SessionListSession(
                id: "1",
                title: "Refactor Navigation",
                worktree: "/Users/demo/opencode-mobile",
                letter: "O",
                letterAccent: .violet,
                subtitle: "/Users/demo/opencode-mobile",
                status: .running,
                caption: "now"
            ),
            SessionListSession(
                id: "2",
                title: "Bug: Header Clipping",
                worktree: "/Users/demo/playground",
                letter: "P",
                letterAccent: .teal,
                subtitle: "/Users/demo/playground",
                status: .idle,
                caption: "14m ago"
            ),
        ],
        filterTabs: [
            SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 2),
            SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 1),
            SessionListFilterTab(id: "/Users/demo/playground", title: "playground", count: 1),
        ],
        selectedFilterId: SessionListFilterTab.allId,
        onFilterSelected: { _ in },
        onSessionSelected: { _ in },
        onAddProject: {},
        onNewSession: {}
    )
    .openCodeTheme()
}
#endif
