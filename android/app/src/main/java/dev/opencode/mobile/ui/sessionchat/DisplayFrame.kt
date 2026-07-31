package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.markdown.stableMarkdownSourceHash

/**
 * Content-addressed revision of plain text for decoration / layout gates.
 * Lives with the internal projector seam (UI package) because [DisplayMessageBody]
 * reuses [SessionChatTimelineBlock] and must not create a domain→UI cycle.
 */
data class ContentRevision(
    val textHash: Int,
    val textLength: Int,
) {
    companion object {
        fun of(plain: String): ContentRevision =
            ContentRevision(
                textHash = stableMarkdownSourceHash(plain),
                textLength = plain.length,
            )
    }
}

/**
 * Internal display handoff for the timeline projector.
 * Public observation remains [dev.opencode.mobile.domain.message.DisplayMessageListUpdate].
 */
data class DisplayFrame(
    val sessionKey: String,
    val frameRevision: Long,
    val sourceRevision: Long,
    val messages: List<DisplayMessageBody>,
)

data class DisplayMessageBody(
    val message: Message,
    val blocks: List<SessionChatTimelineBlock>,
)
