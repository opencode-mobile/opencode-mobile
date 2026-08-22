package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.AgentsApi
import dev.opencode.mobile.domain.connection.ServerProtocol
import dev.opencode.mobile.network.ActiveConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAgentRepository
    @Inject
    constructor(
        private val api: AgentsApi,
        private val adapter: AgentDomainAdapter,
        private val activeConnectionManager: ActiveConnectionManager? = null,
    ) : AgentRepository {
        override suspend fun listSelectableAgents(directory: String?): List<SelectableAgent> =
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    adapter.toSelectableAgentsFromV2(api.listAgentsV2(directory))
                } else {
                    adapter.toSelectableAgents(api.listAgents(directory))
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }

        override suspend fun listMentionableAgents(directory: String?): List<MentionableAgent> =
            try {
                if (activeConnectionManager?.currentProtocolOrProbe() == ServerProtocol.V2) {
                    adapter.toMentionableAgentsFromV2(api.listAgentsV2(directory))
                } else {
                    adapter.toMentionableAgents(api.listAgents(directory))
                }
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
    }
