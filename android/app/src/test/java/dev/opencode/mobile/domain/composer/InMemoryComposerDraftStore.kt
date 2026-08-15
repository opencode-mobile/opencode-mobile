package dev.opencode.mobile.domain.composer

import dev.opencode.mobile.domain.model.ModelSelectionConnectionIdentity

class InMemoryComposerDraftStore : ComposerDraftStore {
    private val saved = mutableMapOf<String, ComposerDraft>()

    override suspend fun load(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): ComposerDraft? = saved[key(connectionIdentity, worktree, sessionId)]

    override suspend fun save(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        draft: ComposerDraft,
    ) {
        val existing = saved[key(connectionIdentity, worktree, sessionId)]
        if (existing?.savedAtEpochMs != null &&
            draft.savedAtEpochMs != null &&
            existing.savedAtEpochMs > draft.savedAtEpochMs
        ) {
            return
        }
        saved[key(connectionIdentity, worktree, sessionId)] = draft
    }

    override suspend fun delete(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) {
        saved.remove(key(connectionIdentity, worktree, sessionId))
    }

    override suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    ) {
        saved.keys
            .filter { it.startsWith("${connectionIdentity.serverBaseUrl}|") && it.endsWith("|$sessionId") }
            .forEach { saved.remove(it) }
    }

    override suspend fun clearAll() {
        saved.clear()
    }

    private fun key(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ) = "${connectionIdentity.serverBaseUrl}|$worktree|$sessionId"
}
