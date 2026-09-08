package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument

data class DisplayMessage(
    val message: Message,
    val renderedMarkdown: MarkdownDocument? = null,
    /**
     * True when [renderedMarkdown] is still a streaming (semantic-fallback) parse
     * kept as a FinalPlaceholder. Final prepare must not settle-reuse these when
     * TextMate is enabled.
     */
    val awaitingTextMateUpgrade: Boolean = false,
    /** Matches [MarkdownHighlightPreferences.epoch] when this row was prepared. */
    val highlightEpoch: Int = 0,
) {
    val id: String get() = message.id
    val text: String get() = message.text
    val isStreaming: Boolean
        get() =
            message.parts.any { part ->
                part is MessagePart.Text && part.isStreaming
            }
}
