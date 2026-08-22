package dev.opencode.mobile.domain.agent

interface AgentRepository {
    suspend fun listSelectableAgents(directory: String? = null): List<SelectableAgent>

    suspend fun listMentionableAgents(directory: String? = null): List<MentionableAgent>
}
