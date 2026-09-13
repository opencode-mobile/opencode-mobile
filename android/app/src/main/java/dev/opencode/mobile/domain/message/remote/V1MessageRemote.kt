package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi

internal class V1MessageRemote(
    private val api: MessagesApi,
    private val adapter: MessageDomainAdapter,
) : MessageRemote {
    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> = api.listMessages(sessionId, directory).map(adapter::toMessage)

    override suspend fun listMessagePage(
        sessionId: String,
        directory: String?,
        query: MessageHistoryQuery,
    ): MessagePage {
        val page =
            api.listMessagesPage(
                sessionId = sessionId,
                directory = directory,
                limit = query.limit,
                before = query.olderCursor,
            )
        return MessagePage(
            messages = page.messages.map(adapter::toMessage),
            olderCursor = page.olderCursor,
            hasOlder = !page.olderCursor.isNullOrBlank(),
        )
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        options: PromptSendOptions,
        directory: String?,
    ) {
        api.sendPromptAsync(sessionId, adapter.toSendRequest(options), directory, options.attachments)
    }
}
