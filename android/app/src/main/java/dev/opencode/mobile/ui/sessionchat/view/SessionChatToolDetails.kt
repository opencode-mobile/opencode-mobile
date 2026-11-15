package dev.opencode.mobile.ui.sessionchat.view

import androidx.compose.runtime.staticCompositionLocalOf
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlocks

/**
 * Builds a tool card's detail when it opens (#1033). A timeline card keeps only what its
 * collapsed row draws; its output and the per-line inspector body are read from the tool part
 * looked up at that moment. Null when the part is gone (the session moved on) or opens nothing.
 */
internal fun interface SessionChatToolDetailResolver {
    fun detail(card: SessionChatTimelineBlock.ToolCard): SessionChatToolDetailTarget?
}

/**
 * The resolver the Session Chat screen and the tool group route provide. The default finds no
 * tool part, so a card outside those screens (a preview) opens only a compaction summary.
 */
internal val LocalSessionChatToolDetailResolver =
    staticCompositionLocalOf { SessionChatToolDetailResolver { card -> SessionChatToolDetails.target(card, tool = null) } }

internal object SessionChatToolDetails {
    /** The detail [card] opens, from the tool part behind it in [messages]. */
    fun target(
        card: SessionChatTimelineBlock.ToolCard,
        messages: List<Message>,
    ): SessionChatToolDetailTarget? = target(card, tool = if (card.opensToolDetail) toolPart(messages, card.blockID) else null)

    /** The detail [card] opens: its compaction summary, or the detail built from [tool]. */
    fun target(
        card: SessionChatTimelineBlock.ToolCard,
        tool: MessagePart.Tool?,
    ): SessionChatToolDetailTarget? {
        card.detailText?.let { text ->
            return SessionChatToolDetailTarget(title = card.title, body = text, bodyIsMarkdown = card.detailIsMarkdown)
        }
        if (!card.opensToolDetail || tool == null) return null
        val detail = SessionChatTimelineBlocks.toolDetail(tool) ?: return null
        val presentation = detail.presentation
        return SessionChatToolDetailTarget(
            title = presentation?.title ?: card.title,
            body = detail.text ?: presentation?.raw.orEmpty(),
            presentation = presentation,
        )
    }

    /** Whether tapping [card] opens the detail sheet (a summary or a tool detail). */
    fun opensDetail(card: SessionChatTimelineBlock.ToolCard): Boolean = card.detailText != null || card.opensToolDetail

    /** The tool part a card with [blockID] was projected from, newest message first. */
    fun toolPart(
        messages: List<Message>,
        blockID: String,
    ): MessagePart.Tool? {
        for (index in messages.indices.reversed()) {
            for (part in messages[index].parts) {
                if (part !is MessagePart.Tool) continue
                val id = part.id ?: continue
                if (PresentationBlockId.direct(id) == blockID) return part
            }
        }
        return null
    }
}
