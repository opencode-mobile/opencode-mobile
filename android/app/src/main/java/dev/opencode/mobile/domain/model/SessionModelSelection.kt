package dev.opencode.mobile.domain.model

data class SessionModelSelection(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
    val agentId: String? = null,
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

    /** Every session choice and new-session preference of one connection (profile removal). */
    suspend fun clearConnection(connectionIdentity: ModelSelectionConnectionIdentity) = Unit

    suspend fun loadNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): SessionModelSelection?

    suspend fun saveNewSessionPreference(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        selection: SessionModelSelection,
    )

    /** The runtime mode last chosen for new sessions on this connection and worktree (T3). */
    suspend fun loadNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
    ): String? = null

    suspend fun saveNewSessionRuntimeMode(
        connectionIdentity: ModelSelectionConnectionIdentity,
        worktree: String,
        runtimeMode: String,
    ) = Unit

    suspend fun clearAll()
}
