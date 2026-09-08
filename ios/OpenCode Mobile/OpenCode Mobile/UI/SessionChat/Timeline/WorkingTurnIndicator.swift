import Foundation

/// Last-timeline-row Session Running chrome. Show iff this session's
/// status is not idle, or a large prompt body is still on the wire.
/// Optimistic busy on send is the same status. Hide when a permission
/// or question dock is waiting on the user. When the last painted row
/// is Thinking, keep this label so the timeline can collapse the reserved
/// Working row in place — do not drop the list identity.
/// Do not hide for settled Thought, tools, or streaming text.
enum WorkingTurnIndicator {
    static func lastIsThinking(messages: [SessionChatRow]) -> Bool {
        guard case .reasoning(_, _, let isStreaming, _)? = messages.last?.blocks.last else {
            return false
        }
        return isStreaming
    }

    static func shouldCollapseReservedRow(lastIsThinking: Bool, label: String?) -> Bool {
        lastIsThinking && label != nil
    }

    static func labelForActiveTurn(
        statusLabel: String?,
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false,
        sendProgressPercent: Int? = nil
    ) -> String? {
        if isAwaitingUser { return nil }
        if isCompacting { return SessionCompactChrome.statusLabel }
        if let sendProgressPercent {
            return PromptSendProgress.label(percent: sendProgressPercent)
        }
        return statusLabel
    }
}
