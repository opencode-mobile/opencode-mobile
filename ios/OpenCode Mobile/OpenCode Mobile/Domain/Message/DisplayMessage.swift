import Foundation

struct DisplayMessage: Sendable {
    let message: Message
    let renderedMarkdown: NativeMarkdownDocument?

    init(message: Message, renderedMarkdown: NativeMarkdownDocument? = nil) {
        self.message = message
        self.renderedMarkdown = renderedMarkdown
    }

    var id: String { message.id }
    var text: String { message.text }
    var isStreaming: Bool {
        message.parts.contains { part in
            if case .text(let textPart) = part {
                return textPart.isStreaming
            }
            return false
        }
    }
}
