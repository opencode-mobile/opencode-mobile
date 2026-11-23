package dev.opencode.mobile.domain.composer

import dev.opencode.mobile.domain.agent.AgentMention
import dev.opencode.mobile.domain.agent.FileMention
import dev.opencode.mobile.domain.agent.SkillMention
import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity
import kotlinx.coroutines.runBlocking

data class ComposerDraft(
    val text: String,
    val agentMentions: List<AgentMention> = emptyList(),
    val fileMentions: List<FileMention> = emptyList(),
    val skillMentions: List<SkillMention> = emptyList(),
    val savedAtEpochMs: Long? = null,
)

interface ComposerDraftStore {
    /**
     * Saves [draft] (a blank one deletes) without suspending the caller, for a screen whose scope
     * is ending. A [load] right after sees it. Stores with real I/O override this.
     */
    fun saveDetached(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        draft: ComposerDraft,
    ) {
        runBlocking {
            if (draft.text.isBlank()) {
                delete(connectionIdentity, worktree, sessionId)
            } else {
                save(connectionIdentity, worktree, sessionId, draft)
            }
        }
    }

    /** Moves entries stored by URL and username under [connectionIdentity]'s profile id. Safe to repeat. */
    suspend fun migrateConnection(
        connectionIdentity: ModelSelectionConnectionIdentity,
        addresses: Set<String>,
    ) = Unit

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
