package dev.opencode.mobile.domain.message

import dev.opencode.mobile.markdown.MarkdownDocument

data class DisplayMessage(
    val message: Message,
    val renderedMarkdown: MarkdownDocument? = null,
) {
    val id: String get() = message.id
    val text: String get() = message.text
    val isStreaming: Boolean
        get() =
            message.parts.any { part ->
                part is MessagePart.Text && part.isStreaming
            }
}
