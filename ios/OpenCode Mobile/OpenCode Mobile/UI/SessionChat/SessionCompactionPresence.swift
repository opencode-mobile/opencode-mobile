import Foundation

/// Detects an in-flight compaction turn from live messages (remote or local).
enum SessionCompactionPresence {
    static func isActive(messages: [Message], isSessionBusy: Bool) -> Bool {
        guard isSessionBusy else { return false }
        let turn = activeTurn(in: messages)
        return turn.contains(where: isCompactionSignal)
    }

    private static func activeTurn(in messages: [Message]) -> ArraySlice<Message> {
        if let lastUser = messages.lastIndex(where: { $0.role == .user }) {
            return messages[lastUser...]
        }
        return messages[...]
    }

    private static func isCompactionSignal(_ message: Message) -> Bool {
        if message.parts.contains(where: {
            if case .compaction = $0 { return true }
            return false
        }) {
            return true
        }
        guard message.role == .assistant else { return false }
        return isCompactionMode(message.mode)
    }

    private static func isCompactionMode(_ raw: String?) -> Bool {
        guard let raw, !raw.isEmpty else { return false }
        return raw.compare("compaction", options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }
}
