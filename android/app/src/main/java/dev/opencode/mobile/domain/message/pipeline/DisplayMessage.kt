package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument

data class DisplayMessage(
    val message: Message,
    val renderedMarkdown: MarkdownDocument? = null,
    /**
     * True when [renderedMarkdown] is still a structure-only parse (a streaming
     * FinalPlaceholder or format-first history) whose code colors arrive with a
     * later Final or HistoryEnrichment prepare. Final must not settle-reuse these.
     */
    val awaitingTextMateUpgrade: Boolean = false,
) {
    val id: String get() = message.id
    val text: String get() = message.text
    val isStreaming: Boolean
        get() =
            message.parts.any { part ->
                part is MessagePart.Text && part.isStreaming
            }
}
