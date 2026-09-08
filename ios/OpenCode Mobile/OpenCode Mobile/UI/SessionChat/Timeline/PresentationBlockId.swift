import Foundation

/// Deterministic presentation `blockID` encoding for Phase 5.
/// Missing server part IDs are not invented here — callers must use recovery
/// or `messageFallback` only when structured build is incomplete.
nonisolated enum PresentationBlockId {
    private static let sep = "\u{001F}"

    nonisolated static func direct(_ partId: String) -> String {
        "p:\(partId)"
    }

    nonisolated static func mergedText(_ orderedPartIds: [String]) -> String {
        precondition(!orderedPartIds.isEmpty, "merged text requires at least one part id")
        if orderedPartIds.count == 1 {
            return direct(orderedPartIds[0])
        }
        return "t:" + orderedPartIds.joined(separator: sep)
    }

    nonisolated static func toolGroup(_ orderedMemberIds: [String]) -> String {
        precondition(orderedMemberIds.count >= 2, "tool group requires at least two member ids")
        return "g:" + orderedMemberIds.joined(separator: sep)
    }

    /// Unstructured fallback when displayable parts lack stable IDs.
    nonisolated static func messageFallback(_ messageId: String) -> String {
        "m:\(messageId)"
    }
}

nonisolated enum SessionChatTimelineBlocksResult: Equatable, Sendable {
    case ok([SessionChatTimelineBlock])
    /// Displayable parts were missing stable server part IDs.
    case missingPartIds
}
