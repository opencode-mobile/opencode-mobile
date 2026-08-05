import SwiftUI

struct SessionListGalleryView: View {
    @State private var selectedFilterId = SessionListFilterTab.allId

    var body: some View {
        SessionList(
            sessions: sessionListGallerySamples,
            filterTabs: sessionListGalleryFilterTabs,
            selectedFilterId: selectedFilterId,
            onFilterSelected: { selectedFilterId = $0 },
            onSessionSelected: { _ in },
            onAddProject: {},
            onNewSession: {}
        )
    }
}

private let sessionListGallerySamples: [SessionListSession] = [
    SessionListSession(
        id: "session-1",
        title: "Refactor Navigation",
        worktree: "/Users/demo/opencode-mobile",
        letter: "O",
        letterAccent: .violet,
        subtitle: "/Users/demo/opencode-mobile",
        status: .running,
        caption: "now"
    ),
    SessionListSession(
        id: "session-2",
        title: "Bug: Header Clipping",
        worktree: "/Users/demo/playground",
        letter: "P",
        letterAccent: .teal,
        subtitle: "/Users/demo/playground",
        status: .idle,
        caption: "14m ago"
    ),
    SessionListSession(
        id: "session-3",
        title: "Add Dark Mode Assets",
        worktree: "/Users/demo/opencode-mobile",
        letter: "O",
        letterAccent: .violet,
        subtitle: "/Users/demo/opencode-mobile",
        status: .ready,
        caption: "3h ago"
    ),
    SessionListSession(
        id: "session-4",
        title: "Review Session List",
        worktree: "/Users/demo/api-client",
        letter: "A",
        letterAccent: .amber,
        subtitle: "/Users/demo/api-client",
        status: .running,
        caption: "2d ago"
    ),
]

private let sessionListGalleryFilterTabs: [SessionListFilterTab] = [
    SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 4),
    SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    SessionListFilterTab(id: "/Users/demo/playground", title: "playground", count: 1),
    SessionListFilterTab(id: "/Users/demo/api-client", title: "api-client", count: 1),
]

#Preview {
    SessionListGalleryView()
        .openCodeTheme()
}
