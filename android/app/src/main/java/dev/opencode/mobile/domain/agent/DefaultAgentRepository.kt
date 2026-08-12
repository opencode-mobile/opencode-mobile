package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.AgentsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.domain.project.ProjectRepository
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAgentRepository
    @Inject
    constructor(
        private val api: AgentsApi,
        private val adapter: AgentDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
        private val projectRepository: ProjectRepository? = null,
    ) : AgentRepository {
        private val protocolMutex = Mutex()

        private suspend fun isV2(): Boolean {
            protocolMutex.withLock {
                activeConnectionManager?.currentProtocol()?.let { return it == ServerProtocol.V2 }
                val projectRepository = projectRepository ?: return false
                runCatching { projectRepository.probeServer() }
                return activeConnectionManager?.currentProtocol() == ServerProtocol.V2
            }
        }

        override suspend fun listMentionableAgents(directory: String?): List<MentionableAgent> =
            try {
                if (isV2()) {
                    adapter.toMentionableAgentsFromV2(api.listAgentsV2(directory))
                } else {
                    adapter.toMentionableAgents(api.listAgents(directory))
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
    }
