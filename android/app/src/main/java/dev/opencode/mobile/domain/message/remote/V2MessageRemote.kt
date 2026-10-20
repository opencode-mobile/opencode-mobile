package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea

internal class V2MessageRemote(
    private val api: MessagesApi,
    private val adapter: MessageDomainAdapter,
) : MessageRemote {
    override suspend fun listMessages(
        sessionId: String,
        directory: String?,
    ): List<Message> {
        val messages = adapter.projectV2(api.listMessagesV2(sessionId, directory))
        return mergeInboxOrUnchanged(sessionId, messages)
    }

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
        // desc / cursor-desc pages are newest-first. Reverse exactly once.
        val chronological = envelope.data.asReversed()
        val olderCursor = envelope.cursor?.next?.takeIf { it.isNotBlank() }
        val messages = adapter.projectV2(chronological)
        return MessagePage(
            messages = if (query.isTail) mergeInboxOrUnchanged(sessionId, messages) else messages,
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

    /**
     * Like the TUI, a shell command leaves the session's agent and model alone: v2 runs it
     * outside the agent loop.
     */
    override suspend fun runShellCommand(
        sessionId: String,
        options: ShellCommandOptions,
        directory: String?,
    ) {
        api.runShellV2(sessionId, adapter.toV2ShellRequest(options))
    }

    private suspend fun mergeInboxOrUnchanged(
        sessionId: String,
        messages: List<Message>,
    ): List<Message> =
        runCatching { adapter.mergeInbox(messages, api.listInboxV2(sessionId)) }
            .onFailure { error ->
                DiagnosticLog.warning(DiagnosticLogArea.Chat, "inbox merge failed session=$sessionId", error)
            }.getOrDefault(messages)
}
