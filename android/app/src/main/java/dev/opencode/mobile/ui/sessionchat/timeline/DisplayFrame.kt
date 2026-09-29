package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessageRole
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

/**
 * Oldest-to-newest by `time.created`, without moving a user the store already
 * placed before an assistant (v2 inbox user rows can be persisted after the reply starts).
 */
internal object TimelineDisplayOrder {
    fun ordered(displayMessages: List<DisplayMessage>): List<DisplayMessage> {
        val inputIndex = displayMessages.mapIndexed { index, display -> display.id to index }.toMap()
        val timeSorted =
            displayMessages
                .withIndex()
                .sortedWith { left, right ->
                    val leftTime = left.value.message.sentAtEpochMs
                    val rightTime = right.value.message.sentAtEpochMs
                    when {
                        leftTime != null && rightTime != null -> {
                            val byTime = leftTime.compareTo(rightTime)
                            if (byTime != 0) {
                                byTime
                            } else {
                                val byId =
                                    left.value.message.id
                                        .compareTo(right.value.message.id)
                                if (byId != 0) byId else left.index.compareTo(right.index)
                            }
                        }
                        leftTime != null -> -1
                        rightTime != null -> 1
                        else -> {
                            val byIndex = left.index.compareTo(right.index)
                            if (byIndex != 0) {
                                byIndex
                            } else {
                                left.value.message.id
                                    .compareTo(right.value.message.id)
                            }
                        }
                    }
                }.map { it.value }
                .toMutableList()

        var i = 0
        while (i < timeSorted.size) {
            val current = timeSorted[i]
            if (current.message.role != MessageRole.Assistant) {
                i += 1
                continue
            }
            val assistantInput = inputIndex[current.id] ?: i
            val displaced = mutableListOf<DisplayMessage>()
            val rest = mutableListOf<DisplayMessage>()
            for (message in timeSorted.drop(i + 1)) {
                val messageInput = inputIndex[message.id] ?: Int.MAX_VALUE
                if (message.message.role == MessageRole.User && messageInput < assistantInput) {
                    displaced += message
                } else {
                    rest += message
                }
            }
            if (displaced.isEmpty()) {
                i += 1
                continue
            }
            displaced.sortBy { inputIndex[it.id] ?: 0 }
            val prefix = timeSorted.take(i)
            timeSorted.clear()
            timeSorted += prefix
            timeSorted += displaced
            timeSorted += current
            timeSorted += rest
            i += displaced.size + 1
        }
        return timeSorted
    }
}
