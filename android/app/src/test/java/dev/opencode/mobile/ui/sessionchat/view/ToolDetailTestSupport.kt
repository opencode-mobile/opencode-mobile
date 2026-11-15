package dev.opencode.mobile.ui.sessionchat.view

import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.PresentationBlockId
import dev.opencode.mobile.ui.sessionchat.SessionChatTimelineBlock

/** A resolver that finds a card's tool part among [parts], as Session Chat finds it in the transcript. */
internal fun toolDetailsFor(parts: List<MessagePart>): SessionChatToolDetailResolver =
    SessionChatToolDetailResolver { card -> SessionChatToolDetails.target(card, toolFor(card, parts)) }

/** The detail [this] card opens, built from its tool part among [parts]. */
internal fun SessionChatTimelineBlock.ToolCard.openDetail(parts: List<MessagePart>): SessionChatToolDetailTarget? =
    SessionChatToolDetails.target(this, toolFor(this, parts))

private fun toolFor(
    card: SessionChatTimelineBlock.ToolCard,
    parts: List<MessagePart>,
): MessagePart.Tool? =
    parts.filterIsInstance<MessagePart.Tool>().firstOrNull { tool ->
        tool.id?.let(PresentationBlockId::direct) ==
            card.blockID
    }
