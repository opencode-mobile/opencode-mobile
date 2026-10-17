import Foundation

nonisolated enum SessionTranscriptAuthority {
    static func lastAssistantNeedsAuthority(_ messages: [Message]) -> Bool {
        let lastUserIndex = messages.lastIndex(where: { $0.role == .user })
        let afterLastUser: ArraySlice<Message>
        if let lastUserIndex {
            afterLastUser = messages[(lastUserIndex + 1)...]
        } else {
            afterLastUser = messages[...]
        }
        guard let lastAssistant = afterLastUser.last(where: { $0.role == .assistant }) else {
            return false
        }
        return !hasVisibleContent(lastAssistant)
    }

    private static func hasVisibleContent(_ message: Message) -> Bool {
        message.parts.contains { part in
            switch part {
            case .text(let text):
                text.isDisplayable && (text.isStreaming || !text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            case .reasoning(let reasoning):
                ThoughtPartChrome.isVisible(reasoning)
            case .tool(let tool):
                ToolPartIdentity.isVisibleOnTimeline(tool)
            case .file:
                true
            case .compaction, .notice:
                false
            }
        }
    }
}
