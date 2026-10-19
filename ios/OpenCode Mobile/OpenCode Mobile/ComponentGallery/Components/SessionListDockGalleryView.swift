import SwiftUI

struct SessionListDockGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var showCreateError = false
    @State private var oneProject = false
    @State private var showProjectPicker = false
    @State private var showNewSessionPicker = false
    @State private var load: SessionListDockGalleryLoad = .none

    private var filterTabs: [SessionListFilterTab] {
        let tabs = oneProject
            ? sessionListDockGalleryOneProjectFilterTabs
            : sessionListDockGalleryFilterTabs
        guard let scopeLoad = load.scopeLoad else { return tabs }
        // The last project stands in for one that was just added.
        let loadingID = tabs.last?.id
        return tabs.map { tab in
            guard tab.id == loadingID else { return tab }
            return .project(worktree: tab.id, title: tab.title, count: 0, load: scopeLoad)
        }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            OpenCodeColors.backgroundBase
                .ignoresSafeArea()

            VStack(spacing: 12) {
                HStack(spacing: 8) {
                    OpenCodeChoiceBadge(
                        label: "1 project",
                        selected: oneProject,
                        action: {
                            oneProject.toggle()
                            selectedFilterId = oneProject
                                ? sessionListDockGalleryOneProjectFilterTabs[0].id
                                : SessionListFilterTab.allId
                        }
                    )
                    OpenCodeChoiceBadge(
                        label: "Create error",
                        selected: showCreateError,
                        action: { showCreateError.toggle() }
                    )
                }
                HStack(spacing: 8) {
                    ForEach(SessionListDockGalleryLoad.allCases, id: \.self) { option in
                        OpenCodeChoiceBadge(
                            label: option.label,
                            selected: load == option,
                            action: {
                                load = option
                                if option != .none, let loadingID = filterTabs.last?.id {
                                    selectedFilterId = loadingID
                                }
                            }
                        )
                    }
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.top, 8)

            OpenCodeSessionListDock(
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                createError: showCreateError ? "Could not create session. Try again." : nil,
                onOpenProjectPicker: { showProjectPicker = true },
                onFilterSelected: { selectedFilterId = $0 },
                onAddProject: {},
                onNewSession: {},
                onNewSessionLongPress: !oneProject
                    ? { showNewSessionPicker = true }
                    : nil
            )
        }
        // Room for the option rows above the dock inside the gallery's scroll view.
        .frame(maxWidth: .infinity, minHeight: 360, maxHeight: .infinity)
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
                onRemoveProject: { worktree in
                    let remaining = filterTabs.filter { $0.id != worktree }
                    selectedFilterId = remaining.first?.id ?? selectedFilterId
                    showProjectPicker = false
                }
            )
        }
        .openCodeBottomSheet(
            isPresented: $showNewSessionPicker,
            title: "New session"
        ) {
            ForEach(filterTabs.filter { !$0.isAll }) { tab in
                OpenCodeBottomSheetEntry(
                    title: tab.title,
                    startDecorator: .icon(.folder),
                    action: { showNewSessionPicker = false }
                )
            }
        }
    }
}

/// Loading states the dock gallery can put on the last project.
enum SessionListDockGalleryLoad: CaseIterable {
    case none
    case loading
    case slow
    case failed

    var label: String {
        switch self {
        case .none: "Loaded"
        case .loading: "Loading"
        case .slow: "Slow"
        case .failed: "Failed"
        }
    }

    var scopeLoad: SessionListScopeLoad? {
        switch self {
        case .none: nil
        case .loading: .loading(isSlow: false)
        case .slow: .loading(isSlow: true)
        case .failed: .failed(message: "Couldn\u{2019}t load sessions")
        }
    }
}

private let sessionListDockGalleryFilterTabs: [SessionListFilterTab] = [
    .all(projectCount: 3, sessionCount: 4),
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    .project(worktree: "/Users/demo/playground", title: "playground", count: 1),
    .project(worktree: "/Users/demo/api-client", title: "api-client", count: 1),
]

private let sessionListDockGalleryOneProjectFilterTabs: [SessionListFilterTab] = [
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
]

#if DEBUG
#Preview {
    SessionListDockGalleryView()
        .openCodeTheme()
}
#endif
