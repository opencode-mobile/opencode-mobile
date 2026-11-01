import Foundation

nonisolated struct DisplayMessage: Sendable {
    let message: Message
    let renderedMarkdown: NativeMarkdownDocument?
    /// True when `renderedMarkdown` is still a structure-only parse (streaming, or
    /// HistoryPlainFirst) waiting for highlight-core colors. Final prepare must not
    /// settle-reuse these.
    let awaitingTextMateUpgrade: Bool

    nonisolated init(
        message: Message,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        awaitingTextMateUpgrade: Bool = false
    ) {
        self.message = message
        self.renderedMarkdown = renderedMarkdown
        self.awaitingTextMateUpgrade = awaitingTextMateUpgrade
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
