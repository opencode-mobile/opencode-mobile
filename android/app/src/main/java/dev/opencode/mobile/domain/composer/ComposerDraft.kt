package dev.opencode.mobile.domain.composer

import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.agent.SkillMention
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity

data class ComposerDraft(
    val text: String,
    val agentMentions: List<AgentMention> = emptyList(),
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
    val savedAtEpochMs: Long? = null,
)

interface ComposerDraftStore {
    suspend fun load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): ComposerDraft?

    suspend fun save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        draft: ComposerDraft,
    )

    suspend fun delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    )

    suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    )

    /** Every draft of one connection (profile removal). */
    suspend fun clearConnection(connectionIdentity: ModelSelectionConnectionIdentity) = Unit

    suspend fun clearAll()
}
