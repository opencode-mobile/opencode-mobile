package dev.opencode.mobile.domain.model

data class SessionModelSelection(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
)

interface SessionModelSelectionStore {
    suspend fun load(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
    ): SessionModelSelection?

    suspend fun save(
        serverBaseUrl: String,
        worktree: String,
        sessionId: String,
        selection: SessionModelSelection,
    )

    suspend fun clearAll()
}
