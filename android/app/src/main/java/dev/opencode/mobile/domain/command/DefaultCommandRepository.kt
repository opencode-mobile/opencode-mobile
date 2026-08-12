package dev.opencode.mobile.domain.command

import dev.opencode.mobile.data.remote.CommandsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCommandRepository
    @Inject
    constructor(
        private val api: CommandsApi,
        private val adapter: CommandDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
        private val projectRepository: ProjectRepository? = null,
    ) : CommandRepository {
        private val protocolMutex = Mutex()

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager?.currentProtocol()?.let { return it == ServerProtocol.V2 }
                val projectRepository = projectRepository ?: return false
                runCatching { projectRepository.probeServer() }
                return activeConnectionManager?.currentProtocol() == ServerProtocol.V2
            }
        }

        override suspend fun listCommands(directory: String?): List<ServerCommand> =
            try {
                if (isV2()) {
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
                if (isV2()) {
                    api.runCommandV2(sessionId, body, directory)
                } else {
                    api.runCommand(sessionId, body, directory)
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
        }
    }
