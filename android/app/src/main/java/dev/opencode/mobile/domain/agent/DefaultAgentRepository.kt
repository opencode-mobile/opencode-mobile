package dev.opencode.mobile.domain.agent

import dev.opencode.mobile.data.remote.AgentsApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAgentRepository
    @Inject
    constructor(
        private val api: AgentsApi,
        private val adapter: AgentDomainAdapter,
    ) : AgentRepository {
        override suspend fun listMentionableAgents(directory: String?): List<MentionableAgent> =
            try {
                adapter.toMentionableAgents(api.listAgents(directory))
            } catch (throwable: Throwable) {
                throw adapter.mapError(throwable)
            }
    }
