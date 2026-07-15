package dev.opencode.mobile.domain.model

data class SessionModelSelection(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
    val savedAtEpochMs: Long? = null,
)

data class ModelSelectionConnectionIdentity(
    val serverBaseUrl: String,
    val username: String,
)

interface SessionModelSelectionStore {
    suspend fun loadSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection?

    suspend fun saveSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    )

    suspend fun deleteSession(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        sessionId: String,
    )

    suspend fun deleteSessionEntries(
        connectionIdentity: ModelSelectionConnectionIdentity,
        sessionId: String,
    )

    suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection?

    suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    )

    suspend fun clearAll()
}
