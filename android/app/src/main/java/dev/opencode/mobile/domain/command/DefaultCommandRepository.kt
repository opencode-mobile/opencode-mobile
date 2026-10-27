package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.CommandsApi
import dev.opencode.mobile.data.remote.MessagesApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.message.inlineAttachments
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCommandRepository
    @Inject
    constructor(
        private val api: CommandsApi,
        private val adapter: CommandDomainAdapter,
        private val messagesApi: MessagesApi,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : CommandRepository {
        override suspend fun listCommands(directory: String?): List<ServerCommand> =
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    val commands = adapter.toServerCommandsFromV2(api.listCommandsV2(directory))
                    val skills = runCatching { api.listSkillsV2(directory) }.getOrDefault(emptyList())
                    adapter.mergeV2SlashCatalog(commands, skills)
                } else {
                    adapter.toServerCommands(api.listCommands(directory))
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }

        override suspend fun runCommand(
            sessionId: String,
            options: CommandRunOptions,
            directory: String?,
        ) {
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    options.mode?.takeIf { it.isNotBlank() }?.let { agent ->
                        messagesApi.switchSessionAgent(sessionId, agent, directory)
                    }
                    adapter.toV2SwitchModelRequest(options)?.let { model ->
                        messagesApi.switchSessionModel(sessionId, model, directory)
                    }
                    api.runCommandV2(sessionId, adapter.toV2RunRequest(options), options.attachments.inlineAttachments())
                } else {
                    api.runCommand(sessionId, adapter.toRunRequest(options), directory, options.attachments)
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
        }
    }
