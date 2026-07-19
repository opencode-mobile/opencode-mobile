package dev.opencode.mobile.domain.agent

interface AgentRepository {
    suspend fun listMentionableAgents(directory: String? = null): List<MentionableAgent>
}
