import SwiftUI

struct SessionListGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId
    @State private var oneProject = false
    @State private var showProjectPicker = false
    @State private var projectPickerQuery = ""

    private var filterTabs: [SessionListFilterTab] {
        oneProject ? sessionListGalleryOneProjectFilterTabs : sessionListGalleryFilterTabs
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            SessionList(
                sessions: sessionListGallerySamples,
                filterTabs: filterTabs,
                selectedFilterId: selectedFilterId,
                onOpenProjectPicker: { showProjectPicker = true },
                onSessionSelected: { _ in },
                onAddProject: {},
                onNewSession: {}
            )
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
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .openCodeBottomSheet(
            isPresented: $showProjectPicker,
            title: "Filter by project",
            contentMode: .form,
            alwaysFixedHeight: true,
            pinnedContent: {
                OpenCodeTextInput(text: $projectPickerQuery, hint: "Search projects")
            }
        ) {
            SessionListProjectPickerRows(
                tabs: filterTabs,
                query: projectPickerQuery,
                onSelect: { id in
                    selectedFilterId = id
                    showProjectPicker = false
                    projectPickerQuery = ""
                },
                onRemoveProject: nil
            )
        }
        .onChange(of: showProjectPicker) { _, visible in
            if !visible { projectPickerQuery = "" }
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
