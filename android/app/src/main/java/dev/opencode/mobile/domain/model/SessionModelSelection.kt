package dev.opencode.mobile.domain.model

import dev.opencode.mobile.domain.connection.ConnectionProfile

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
    /** Stored entries key on this when set, so they survive a move between saved addresses. */
    val profileId: String = "",
)

fun ConnectionProfile.modelSelectionIdentity(): ModelSelectionConnectionIdentity = ModelSelectionConnectionIdentity(baseUrl, username, id)

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

    /**
     * Moves entries stored by URL and username under [connectionIdentity]'s profile id. Safe to
     * repeat. Entries with no username move only from [exclusiveAddresses], the addresses no
     * other profile uses, because they cannot say which profile wrote them.
     */
    suspend fun migrateConnection(
        connectionIdentity: ModelSelectionConnectionIdentity,
        addresses: Set<String>,
        exclusiveAddresses: Set<String>,
    ) = Unit

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
