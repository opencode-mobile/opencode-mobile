package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProtocolSelectedMessageRemote
    @Inject
    constructor(
        api: MessagesApi,
        adapter: MessageDomainAdapter,
        activeConnectionManager: ActiveConnectionManager? = null,
    ) : MessageRemote {
        private val v1 = V1MessageRemote(api, adapter)
        private val v2 = V2MessageRemote(api, adapter)
        private val activeConnectionManager = activeConnectionManager

        private suspend fun selected(): MessageRemote =
            if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) v2 else v1

        override suspend fun listMessages(
            sessionId: String,
            directory: String?,
        ): List<Message> = selected().listMessages(sessionId, directory)

        override suspend fun listMessagePage(
            sessionId: String,
            directory: String?,
            query: MessageHistoryQuery,
        ): MessagePage = selected().listMessagePage(sessionId, directory, query)

        override suspend fun sendPromptAsync(
            sessionId: String,
            options: PromptSendOptions,
            directory: String?,
        ) {
            selected().sendPromptAsync(sessionId, options, directory)
        }

        override suspend fun runShellCommand(
            sessionId: String,
            options: ShellCommandOptions,
            directory: String?,
        ) {
            selected().runShellCommand(sessionId, options, directory)
        }
    }
