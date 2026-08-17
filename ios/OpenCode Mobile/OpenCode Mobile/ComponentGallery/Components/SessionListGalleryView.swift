import SwiftUI

struct SessionListGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var oneProject = false
    @State private var useProjectPicker = false
    @State private var showProjectPicker = false

    private var filterTabs: [SessionListFilterTab] {
        oneProject ? sessionListGalleryOneProjectFilterTabs : sessionListGalleryFilterTabs
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            SessionList(
                sessions: sessionListGallerySamples,
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                useProjectPicker: useProjectPicker,
                onOpenProjectPicker: { showProjectPicker = true },
                onFilterSelected: { selectedFilterId = $0 },
                onSessionSelected: { _ in },
                onAddProject: {},
                onNewSession: {}
            )
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
                OpenCodeChoiceBadge(
                    label: "Picker",
                    selected: useProjectPicker,
                    action: { useProjectPicker.toggle() }
                )
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
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
            updatedAtEpochMs: now
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
            updatedAtEpochMs: now - 3 * 3_600_000
        ),
        SessionListSession(
            id: "session-4",
            title: "Review Session List",
            worktree: "/Users/demo/api-client",
            letter: "A",
            letterAccent: .amber,
            subtitle: "/Users/demo/api-client",
            status: .running,
            caption: "2d ago",
            updatedAtEpochMs: now - 2 * 86_400_000
        ),
    ]
}()

private let sessionListGalleryFilterTabs: [SessionListFilterTab] = [
    .all(projectCount: 3, sessionCount: 4),
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    .project(worktree: "/Users/demo/playground", title: "playground", count: 1),
    .project(worktree: "/Users/demo/api-client", title: "api-client", count: 1),
]

private let sessionListGalleryOneProjectFilterTabs: [SessionListFilterTab] = [
    .project(worktree: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
]

#Preview {
    SessionListGalleryView()
        .openCodeTheme()
}
