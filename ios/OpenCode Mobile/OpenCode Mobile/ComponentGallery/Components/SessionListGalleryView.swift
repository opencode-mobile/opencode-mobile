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
    SessionListFilterTab(id: SessionListFilterTab.allId, title: "All", count: 4),
    SessionListFilterTab(id: "/Users/demo/opencode-mobile", title: "opencode-mobile", count: 2),
    SessionListFilterTab(id: "/Users/demo/playground", title: "playground", count: 1),
    SessionListFilterTab(id: "/Users/demo/api-client", title: "api-client", count: 1),
]

#Preview {
    SessionListGalleryView()
        .openCodeTheme()
}
