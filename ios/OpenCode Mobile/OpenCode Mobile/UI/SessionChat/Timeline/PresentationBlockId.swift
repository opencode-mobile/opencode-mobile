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

    /// Chip-timeline step paragraph: its text part ids followed by its tool part ids.
    /// Chip timeline: a run of settled thoughts rendered as one row. The id is
    /// the previous row id followed by the new part id, so a growing run keeps a
    /// stable prefix.
    nonisolated static func mergedThought(_ ids: [String]) -> String {
        "th:" + ids.joined(separator: sep)
    }

    nonisolated static func stepParagraph(_ orderedPartIds: [String]) -> String {
        precondition(!orderedPartIds.isEmpty, "step paragraph requires at least one part id")
        return "s:" + orderedPartIds.joined(separator: sep)
    }

    /// Chip-timeline step paragraph identity: its first part id. Unlike the
    /// block id it does not change while later tools join the paragraph.
    nonisolated static func stepParagraphIdentity(_ firstPartId: String) -> String {
        "sp:" + firstPartId
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
