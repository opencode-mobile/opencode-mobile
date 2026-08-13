package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.CommandsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCommandRepository
    @Inject
    constructor(
        private val api: CommandsApi,
        private val adapter: CommandDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : CommandRepository {
        override suspend fun listCommands(directory: String?): List<ServerCommand> =
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    adapter.toServerCommandsFromV2(api.listCommandsV2(directory))
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
                val body = adapter.toRunRequest(options)
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    api.runCommandV2(sessionId, body, directory)
                } else {
                    api.runCommand(sessionId, body, directory)
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
        }
    }
