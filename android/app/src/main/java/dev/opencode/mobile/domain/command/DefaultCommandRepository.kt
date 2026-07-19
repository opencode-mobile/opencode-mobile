package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.CommandsApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCommandRepository
    @Inject
    constructor(
        private val api: CommandsApi,
        private val adapter: CommandDomainAdapter,
    ) : CommandRepository {
        override suspend fun listCommands(directory: String?): List<ServerCommand> =
            try {
                adapter.toServerCommands(api.listCommands(directory))
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }

        override suspend fun runCommand(
            sessionId: String,
            options: CommandRunOptions,
            directory: String?,
        ) {
            try {
                api.runCommand(
                    sessionId = sessionId,
                    body = adapter.toRunRequest(options),
                    directory = directory,
                )
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
        }
    }
