package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi

internal class V2MessageRemote(
    private val api: MessagesApi,
    private val adapter: MessageDomainAdapter,
) : MessageRemote {
    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = adapter.projectV2(api.listMessagesV2(sessionId, directory))

    override suspend fun listMessagePage(
        sessionId: String,
        directory: String?,
        query: MessageHistoryQuery,
    ): MessagePage {
        val envelope =
            api.listMessagesV2Page(
                sessionId = sessionId,
                directory = directory,
                limit = query.limit,
                newestFirst = query.isTail,
                cursor = query.olderCursor,
            )
        val chronological = envelope.data.asReversed()
        val olderCursor = envelope.cursor?.next?.takeIf { it.isNotBlank() }
        return MessagePage(
            messages = adapter.projectV2(chronological),
            olderCursor = olderCursor,
            hasOlder = olderCursor != null,
        )
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) {
        options.mode?.takeIf { it.isNotBlank() }?.let { agent ->
            api.switchSessionAgent(sessionId, agent, directory)
        }
        adapter.toV2SwitchModelRequest(options)?.let { model ->
            api.switchSessionModel(sessionId, model, directory)
        }
        api.sendPromptV2(sessionId, adapter.toV2PromptRequest(options), directory, options.attachments)
    }
}
