import Foundation

/// In-chat Working/Retrying/Compacting chrome for an active turn.
/// Reuses product labels from `EventDomainAdapter` (`Working` / `Retrying`),
/// plus `SessionCompactChrome.statusLabel` while compact is in flight.
enum WorkingTurnIndicator {
    static func labelForActiveTurn(
        statusLabel: String?,
        messages: [SessionChatRow],
        isCompacting: Bool = false
    ) -> String? {
        if isCompacting { return SessionCompactChrome.statusLabel }
        guard let statusLabel else { return nil }
        if hasVisibleAssistantContentAfterLastUser(messages) { return nil }
        return statusLabel
    }

    private static func hasVisibleAssistantContentAfterLastUser(_ messages: [SessionChatRow]) -> Bool {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let candidates: ArraySlice<SessionChatRow>
        if let lastUserIndex {
            candidates = messages[(lastUserIndex + 1)...]
        } else {
            candidates = messages[...]
        }
        return candidates.contains { $0.role == .assistant && hasVisibleContent($0) }
    }

    private static func hasVisibleContent(_ message: SessionChatRow) -> Bool {
        if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return true
        }
        return message.blocks.contains { block in
            switch block {
            case let .text(_, text, _):
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .attachment, .toolCard, .toolGroup:
                true
            }
        }
    }
}
