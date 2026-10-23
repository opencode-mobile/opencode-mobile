import SwiftUI

struct SessionListGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var oneProject = false
    @State private var rowStyle: HomeSessionRowStyle = .compact
    @State private var showProjectPicker = false
    @State private var load: SessionListDockGalleryLoad = .none

    /// The loading project stands in for one just added: it has no sessions yet.
    private static let loadingWorktree = "/Users/demo/opencode"

    private var filterTabs: [SessionListFilterTab] {
        let tabs = oneProject ? sessionListGalleryOneProjectFilterTabs : sessionListGalleryFilterTabs
        guard let scopeLoad = load.scopeLoad else { return tabs }
        return tabs + [.project(worktree: Self.loadingWorktree, title: "opencode", count: 0, load: scopeLoad)]
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            SessionList(
                sessions: sessionListGallerySamples,
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                onOpenProjectPicker: { showProjectPicker = true },
                onFilterSelected: { selectedFilterId = $0 },
                onSessionSelected: { _ in },
                onAddProject: {},
                onNewSession: {},
                onRetryScopeLoad: { _ in load = .loading },
                rowStyle: rowStyle
            )
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "1 project",
                        selected: oneProject,
                        action: {
                            oneProject.toggle()
                            selectedFilterId = oneProject
                                ? sessionListGalleryOneProjectFilterTabs[0].id
                                : SessionListFilterTab.allId
                        }
                    )
                    ForEach(HomeSessionRowStyle.allCases, id: \.self) { style in
                        OpenCodeChoiceBadge(
                            label: style.title,
                            selected: rowStyle == style,
                            action: { rowStyle = style }
                        )
                    }
                }
                HStack(spacing: 8) {
                    ForEach(SessionListDockGalleryLoad.allCases, id: \.self) { option in
                        OpenCodeChoiceBadge(
                            label: option.label,
                            selected: load == option,
                            action: {
                                load = option
                                selectedFilterId = option == .none
                                    ? (oneProject ? sessionListGalleryOneProjectFilterTabs[0].id : SessionListFilterTab.allId)
                                    : Self.loadingWorktree
                            }
                        )
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .openCodeBottomSheet(
            isPresented: $showProjectPicker,
            title: "Filter by project",
            // Matches the app's Filter by project sheet; .dismiss is tracked in #680.
            height: .half,
            keyboard: .keep
        ) {
            SessionListProjectPickerRows(
                tabs: filterTabs,
                onSelect: { id in
                    selectedFilterId = id
                    showProjectPicker = false
                },
                onRemoveProject: nil
            )
        }
    }
}

private let sessionListGallerySamples: [SessionListSession] = {
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    return [
        SessionListSession(
            id: "session-1",
            title: "Refactor Navigation",
            worktree: "/Users/demo/opencode-mobile",
            letter: "O",
            letterAccent: .violet,
            subtitle: "/Users/demo/opencode-mobile",
            status: .running,
            caption: "now",
            updatedAtEpochMs: now,
            projectName: "opencode-mobile",
            worktreeLabel: "803-compact-home-session-rows-with-a-classic-option"
        ),
        SessionListSession(
            id: "session-stale",
            title: "Resume Cached Chat",
            worktree: "/Users/demo/opencode-mobile",
            letter: "O",
            letterAccent: .violet,
            subtitle: "/Users/demo/opencode-mobile",
            status: .running,
            statusIsStale: true,
            caption: "14m ago",
            updatedAtEpochMs: now - 14 * 60_000,
            projectName: "opencode-mobile"
        ),
        SessionListSession(
            id: "session-2",
            title: "Bug: Header Clipping",
            worktree: "/Users/demo/playground",
            letter: "P",
            letterAccent: .teal,
            subtitle: "/Users/demo/playground",
            status: .idle,
            caption: "14m ago",
            updatedAtEpochMs: now - 14 * 60_000
        ),
        SessionListSession(
            id: "session-3",
            title: "Add Dark Mode Assets",
            worktree: "/Users/demo/opencode-mobile",
            letter: "O",
            letterAccent: .violet,
            subtitle: "/Users/demo/opencode-mobile",
            status: .ready,
            caption: "3h ago",
            updatedAtEpochMs: now - 3 * 3_600_000,
            projectName: "opencode-mobile",
            worktreeLabel: "opencode-mobile-779"
        ),
        SessionListSession(
            id: "session-4",
            title: "Review Session List",
            worktree: "/Users/demo/api-client",
            letter: "A",
            letterAccent: .amber,
            subtitle: "/Users/demo/api-client",
            status: .retrying(attempt: 2),
            caption: "2d ago",
            updatedAtEpochMs: now - 2 * 86_400_000,
            projectName: "api-client-for-the-enterprise-billing-platform",
            worktreeLabel: "ope-1042-retry"
        ),
    ]
}()

private let sessionListGalleryFilterTabs: [SessionListFilterTab] = [
    .all(projectCount: 3, sessionCount: 5),
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 3),
    .project(worktree: "/Users/demo/playground", title: "playground", count: 1),
    .project(worktree: "/Users/demo/api-client", title: "api-client", count: 1),
]

private let sessionListGalleryOneProjectFilterTabs: [SessionListFilterTab] = [
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 3),
]

#Preview {
    SessionListGalleryView()
        .openCodeTheme()
}
