import SwiftUI

struct SessionListDockGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var showCreateError = false
    @State private var oneProject = false

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
                filterTabs: oneProject
                    ? sessionListDockGalleryOneProjectFilterTabs
                    : sessionListDockGalleryFilterTabs,
                selectedFilterId: selectedFilterId,
                createError: showCreateError ? "Could not create session. Try again." : nil,
                onFilterSelected: { selectedFilterId = $0 },
                onAddProject: {},
                onNewSession: {},
                onRemoveProject: { _ in selectedFilterId = SessionListFilterTab.allId }
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private let sessionListDockGalleryFilterTabs: [SessionListFilterTab] = [
    SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 4),
    SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    SessionListFilterTab(id: "/Users/demo/playground", title: "playground", count: 1),
    SessionListFilterTab(id: "/Users/demo/api-client", title: "api-client", count: 1),
]

private let sessionListDockGalleryOneProjectFilterTabs: [SessionListFilterTab] = [
    SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
]

#if DEBUG
#Preview {
    SessionListDockGalleryView()
        .openCodeTheme()
}
#endif
