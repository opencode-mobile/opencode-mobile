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
        if hasInProgressAssistantContentAfterLastUser(messages) { return nil }
        return statusLabel
    }

    private static func hasInProgressAssistantContentAfterLastUser(_ messages: [SessionChatRow]) -> Bool {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let candidates: ArraySlice<SessionChatRow>
        if let lastUserIndex {
            candidates = messages[(lastUserIndex + 1)...]
        } else {
            candidates = messages[...]
        }
        return candidates.contains { $0.role == .assistant && hasInProgressContent($0) }
    }

    private static func hasInProgressContent(_ message: SessionChatRow) -> Bool {
        if message.isStreaming { return true }
        if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return true
        }
        return message.blocks.contains { block in
            switch block {
            case let .text(_, text, _):
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case let .reasoning(_, text, isStreaming):
                isStreaming || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .attachment, .childSessionRow:
                true
            case let .toolCard(_, card):
                card.status.isInProgress
            case let .toolGroup(_, group):
                group.status.isInProgress || group.members.contains { $0.status.isInProgress }
            }
        }
    }
}

private extension ToolStatus {
    var isInProgress: Bool {
        self == .pending || self == .running
    }
}
