package dev.opencode.mobile.ui.sessionchat

import dev.opencode.mobile.domain.message.DisplayMessage
import dev.opencode.mobile.domain.message.Message
import dev.opencode.mobile.domain.message.MessagePart
import dev.opencode.mobile.domain.message.MessageRole
import dev.opencode.mobile.domain.message.NoticeModelRef
import dev.opencode.mobile.domain.message.TimelineNotice

/**
 * Agent and model change notices for sessions whose server records none (v1).
 *
 * v2 stores `agent-switched` / `model-switched` messages and the timeline draws
 * them. v1 only stamps each user message with its agent and model, so the same
 * notices are derived here from consecutive user messages: one before each user
 * message whose agent or model differs from the previous user message's. Like
 * v2, the first agent and model draw no notice. If the list holds any server
 * agent or model notice the server owns them and nothing is derived.
 */
object TimelineChangeNotices {
    const val DERIVED_ID_PREFIX = "derived-notice"

    fun withDerivedNotices(ordered: List<DisplayMessage>): List<DisplayMessage> {
        if (ordered.any { hasServerChangeNotice(it.message) }) return ordered

        val result = ArrayList<DisplayMessage>(ordered.size)
        var agent: String? = null
        var model: NoticeModelRef? = null
        for (display in ordered) {
            val message = display.message
            if (message.role == MessageRole.User && !isCompaction(message)) {
                val nextAgent = message.mode?.takeIf { it.isNotBlank() }
                if (nextAgent != null && nextAgent != agent) {
                    agent?.let { previous ->
                        result +=
                            notice("$DERIVED_ID_PREFIX:agent:${message.id}", TimelineNotice.AgentChanged(nextAgent, previous), message)
                    }
                    agent = nextAgent
                }
                val modelId = (message.modelId ?: message.model)?.takeIf { it.isNotBlank() }
                if (modelId != null) {
                    val nextModel =
                        NoticeModelRef(
                            providerId = message.modelProviderId.orEmpty(),
                            modelId = modelId,
                            variant = message.variant?.takeIf { it.isNotBlank() },
                        )
                    if (nextModel != model) {
                        model?.let { previous ->
                            result +=
                                notice("$DERIVED_ID_PREFIX:model:${message.id}", TimelineNotice.ModelChanged(nextModel, previous), message)
                        }
                        model = nextModel
                    }
                }
            }
            result += display
        }
        return result
    }

    private fun notice(
        id: String,
        notice: TimelineNotice,
        before: Message,
    ): DisplayMessage =
        DisplayMessage(
            message =
                Message(
                    id = id,
                    role = MessageRole.Notice,
                    text = "",
                    parts = listOf(MessagePart.Notice(id = id, notice = notice)),
                    sentAtEpochMs = before.sentAtEpochMs,
                    completedAtEpochMs = before.sentAtEpochMs,
                ),
        )

    private fun hasServerChangeNotice(message: Message): Boolean =
        message.parts.any { part ->
            part is MessagePart.Notice &&
                (part.notice is TimelineNotice.AgentChanged || part.notice is TimelineNotice.ModelChanged)
        }

    private fun isCompaction(message: Message): Boolean = message.parts.any { it is MessagePart.Compaction }
}
