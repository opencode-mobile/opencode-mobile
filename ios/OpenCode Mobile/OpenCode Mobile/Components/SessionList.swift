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

struct SessionList: View {
    let sessions: [SessionListSession]
    let onSessionSelected: (SessionListSession) -> Void

    var body: some View {
        if sessions.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(sessions) { session in
                    OpenCodeListItem(
                        title: session.title,
                        subtitle: compactHomePath(session.subtitle),
                        status: listItemStatus(for: session.status),
                        caption: session.caption.isEmpty ? nil : session.caption,
                        style: .plain,
                        startDecorator: .letter(session.letter, session.letterAccent),
                        action: { onSessionSelected(session) }
                    )
                }
            }
        }
    }

    private func listItemStatus(for status: SessionListStatus) -> OpenCodeListItemStatus {
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
        onSessionSelected: { _ in }
    )
    .padding()
    .openCodeTheme()
}
#endif
