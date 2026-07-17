import SwiftUI

struct SessionListGalleryView: View {
    var body: some View {
        SessionList(
            sessions: sessionListGallerySamples,
            onSessionSelected: { _ in }
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
        status: .idle,
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

#Preview {
    SessionListGalleryView()
        .padding()
        .openCodeTheme()
}
