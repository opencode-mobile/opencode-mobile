import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle. Optimistic busy on send is the same status. Hide
/// when a permission or question dock is waiting on the user, or when the
/// last painted row is Thinking (that row already occupies the bottom).
/// Do not hide for settled Thought, tools, or streaming text.
enum WorkingTurnIndicator {
    static func lastIsThinking(messages: [SessionChatRow]) -> Bool {
        guard case .reasoning(_, _, let isStreaming, _)? = messages.last?.blocks.last else {
            return false
        }
        return isStreaming
    }

    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false,
        lastIsThinking: Bool = false
    ) -> String? {
        if isAwaitingUser { return nil }
        if lastIsThinking { return nil }
        if isCompacting { return SessionCompactChrome.statusLabel }
        return statusLabel
    }
}
