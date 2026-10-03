import SwiftUI

struct SessionListDockGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var showCreateError = false
    @State private var oneProject = false
    @State private var showProjectPicker = false
    @State private var showNewSessionPicker = false

    private var filterTabs: [SessionListFilterTab] {
        oneProject
            ? sessionListDockGalleryOneProjectFilterTabs
            : sessionListDockGalleryFilterTabs
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .openCodeBottomSheet(
            isPresented: $showProjectPicker,
            title: "Filter by project",
            contentMode: .form
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
