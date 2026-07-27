import Foundation

struct DisplayMessage: Sendable {
    let message: Message
    let renderedMarkdown: NativeMarkdownDocument?

    nonisolated init(message: Message, renderedMarkdown: NativeMarkdownDocument? = nil) {
        self.message = message
        self.renderedMarkdown = renderedMarkdown
    }

    nonisolated var id: String { message.id }
    nonisolated var text: String { message.text }
    nonisolated var isStreaming: Bool {
        message.parts.contains { part in
            if case .text(let textPart) = part {
                return textPart.isStreaming
            }
            return false
        }
    }
}
