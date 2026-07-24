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

    @State private var bottomSafeAreaInset: CGFloat = 0
    @State private var dockHeight: CGFloat = 0

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
        sessions.visibleForFilter(selectedFilterId)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(visibleSessions) { session in
                        OpenCodeListItem(
                            title: session.title,
                            subtitle: compactHomePath(session.subtitle),
                            status: listItemStatus(session.status),
                            caption: session.caption.isEmpty ? nil : session.caption,
                            startDecorator: .letter(session.letter, session.letterAccent),
                            action: { onSessionSelected(session) }
                        )
                        Divider()
                            .overlay(OpenCodeColors.borderWeakerBase)
                            .padding(.leading, 60)
                    }
                }
                Color.clear.frame(height: 168)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(OpenCodeColors.backgroundBase)

            OpenCodeBottomFade(
                height: max(dockHeight + OpenCodeBottomFade.overhang, OpenCodeBottomFade.overhang)
                    + bottomSafeAreaInset
            )
            .offset(y: bottomSafeAreaInset)
            .ignoresSafeArea(edges: .bottom)

            OpenCodeSessionListDock(
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                createError: createError,
                onFilterSelected: onFilterSelected,
                onAddProject: onAddProject,
                onNewSession: onNewSession
            )
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { dockHeight = geometry.size.height }
                        .onChange(of: geometry.size.height) { _, height in
                            dockHeight = height
                        }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(OpenCodeColors.backgroundBase)
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { bottomSafeAreaInset = geometry.safeAreaInsets.bottom }
                    .onChange(of: geometry.safeAreaInsets.bottom) { _, inset in
                        if inset > 0 { bottomSafeAreaInset = inset }
                    }
            }
        }
    }

    private func listItemStatus(_ status: SessionListStatus) -> OpenCodeListItemStatus {
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

extension Array where Element == SessionListSession {
    func visibleForFilter(_ selectedFilterId: String) -> [SessionListSession] {
        if selectedFilterId == SessionListFilterTab.allId {
            return self
        }
        return filter { $0.worktree == selectedFilterId }
    }
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
