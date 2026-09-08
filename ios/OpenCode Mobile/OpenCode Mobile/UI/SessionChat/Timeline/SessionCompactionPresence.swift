import Foundation

/// Detects an in-flight compaction turn from live messages (remote or local).
enum SessionCompactionPresence {
    static func isActive(messages: [Message], isSessionBusy: Bool) -> Bool {
        guard isSessionBusy else { return false }
        let turn = activeTurn(in: messages)
        return turn.contains(where: isCompactionSignal)
    }

    static func completedCompactionIDs(in messages: [Message]) -> Set<String> {
        Set(
            messages.flatMap { message in
                message.parts.compactMap { part -> String? in
                    guard case let .compaction(compaction) = part, compaction.completed else { return nil }
                    return compaction.id ?? message.id
                }
            }
        )
    }

    private static func activeTurn(in messages: [Message]) -> ArraySlice<Message> {
        if let lastUser = messages.lastIndex(where: { $0.role == .user }) {
            return messages[lastUser...]
        }
        return messages[...]
    }

    private static func isCompactionSignal(_ message: Message) -> Bool {
        let compactionParts = message.parts.compactMap { part -> CompactionPart? in
            guard case let .compaction(compaction) = part else { return nil }
            return compaction
        }
        if compactionParts.contains(where: { !$0.completed }) { return true }
        if compactionParts.contains(where: \.completed) { return false }
        guard message.role == .assistant else { return false }
        return isCompactionMode(message.mode)
    }

    private static func isCompactionMode(_ raw: String?) -> Bool {
        guard let raw, !raw.isEmpty else { return false }
        return raw.compare("compaction", options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }
}
