import Foundation

/// In-chat Working/Thinking/Retrying/Compacting chrome for an active turn.
/// Reuses product labels from `EventDomainAdapter` (`Working` / `Retrying`),
/// plus `SessionCompactChrome.statusLabel` while compact is in flight.
/// After settled assistant content, a still-busy turn shows `thinkingLabel`
/// unless Retrying. Working is only for “no assistant turn yet.” An empty
/// or still-hidden assistant slot is the wait — do not paint Working over
/// it or it blinks off as soon as the first part lands.
/// In-flight questions are hidden from the timeline and shown in the dock —
/// that dock is the wait state, so do not add a second Thinking line.
enum WorkingTurnIndicator {
    static let thinkingLabel = "Thinking"

    static func labelForActiveTurn(
        statusLabel: String?,
        messages: [SessionChatRow],
        isCompacting: Bool = false,
        isAwaitingUser: Bool = false,
        assistantTurnExists: Bool = false
    ) -> String? {
        if isCompacting { return SessionCompactChrome.statusLabel }
        if isAwaitingUser { return nil }
        guard let statusLabel else { return nil }
        if hasInProgressAssistantContentAfterLastUser(messages) { return nil }
        if statusLabel == "Working", hasSettledAssistantContentAfterLastUser(messages) {
            return thinkingLabel
        }
        if statusLabel == "Working",
           assistantTurnExists || !assistantsAfterLastUser(messages).isEmpty
        {
            return nil
        }
        return statusLabel
    }

    static func assistantTurnExists(_ messages: [Message]) -> Bool {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let after: ArraySlice<Message>
        if let lastUserIndex {
            after = messages[(lastUserIndex + 1)...]
        } else {
            after = messages[...]
        }
        return after.contains { message in
            message.role == .assistant
                && !(message.mode?.compare("compaction", options: [.caseInsensitive]) == .orderedSame)
        }
    }

    private static func assistantsAfterLastUser(_ messages: [SessionChatRow]) -> [SessionChatRow] {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let candidates: ArraySlice<SessionChatRow>
        if let lastUserIndex {
            candidates = messages[(lastUserIndex + 1)...]
        } else {
            candidates = messages[...]
        }
        return candidates.filter { $0.role == .assistant && !isCompactionSnapshot($0) }
    }

    private static func isCompactionSnapshot(_ message: SessionChatRow) -> Bool {
        guard !message.metadataLabel.isEmpty else { return false }
        let mode = message.metadataLabel.split(separator: " · ", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? message.metadataLabel
        return mode.compare("compaction", options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }

    private static func hasInProgressAssistantContentAfterLastUser(_ messages: [SessionChatRow]) -> Bool {
        assistantsAfterLastUser(messages).contains(where: hasInProgressContent)
    }

    private static func hasSettledAssistantContentAfterLastUser(_ messages: [SessionChatRow]) -> Bool {
        assistantsAfterLastUser(messages).contains(where: hasSettledContent)
    }

    private static func hasInProgressContent(_ message: SessionChatRow) -> Bool {
        if message.isStreaming { return true }
        return message.blocks.contains { block in
            switch block {
            case let .reasoning(_, _, isStreaming, _):
                isStreaming
            case let .toolCard(_, card):
                card.status.isInProgress
            case let .toolGroup(_, group):
                group.status.isInProgress || group.members.contains { $0.status.isInProgress }
            case .text, .attachment, .childSessionRow:
                false
            }
        }
    }

    private static func hasSettledContent(_ message: SessionChatRow) -> Bool {
        if !message.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return true
        }
        return message.blocks.contains { block in
            switch block {
            case let .text(_, text, _):
                !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case let .reasoning(_, text, isStreaming, _):
                !isStreaming && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .attachment, .childSessionRow:
                true
            case let .toolCard(_, card):
                !card.status.isInProgress
            case let .toolGroup(_, group):
                !group.status.isInProgress && group.members.allSatisfy { !$0.status.isInProgress }
            }
        }
    }
}

private extension ToolStatus {
    var isInProgress: Bool {
        self == .pending || self == .running
    }
}
