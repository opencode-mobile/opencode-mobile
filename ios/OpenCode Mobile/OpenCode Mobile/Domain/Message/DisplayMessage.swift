import Foundation

nonisolated struct DisplayMessage: Sendable {
    let message: Message
    let renderedMarkdown: NativeMarkdownDocument?
    /// True when `renderedMarkdown` is still a streaming (semantic-fallback) parse
    /// kept as a FinalPlaceholder. Final prepare must not settle-reuse these when
    /// TextMate is enabled.
    let awaitingTextMateUpgrade: Bool
    /// Matches `MarkdownHighlightPreferences.epoch` when this row was prepared.
    let highlightEpoch: Int

    nonisolated init(
        message: Message,
        renderedMarkdown: NativeMarkdownDocument? = nil,
        awaitingTextMateUpgrade: Bool = false,
        highlightEpoch: Int = 0
    ) {
        self.message = message
        self.renderedMarkdown = renderedMarkdown
        self.awaitingTextMateUpgrade = awaitingTextMateUpgrade
        self.highlightEpoch = highlightEpoch
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
