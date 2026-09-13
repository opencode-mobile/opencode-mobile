package dev.opencode.mobile.domain.message

/** Observation health for a session transcript projection. */
sealed interface MessageSyncHealth {
    data object Fresh : MessageSyncHealth

    data object Stale : MessageSyncHealth

    data object Syncing : MessageSyncHealth

    data class Failed(
        val lastError: Throwable,
    ) : MessageSyncHealth

    val isHealthy: Boolean
        get() = this is Fresh

    val showsCatchUpChrome: Boolean
        get() = this !is Fresh
}

/** REST authority-window result. Discarded means live local state won the race. */
enum class AuthorityWindowOutcome {
    Accepted,
    Discarded,
    Failed,
}

/** Complete ownership key for observation, fingerprints, and store slots. */
data class MessageSessionKey(
    val connectionProfileId: String,
    val directory: String,
    val sessionId: String,
) {
    companion object {
        fun of(
            connectionProfileId: String,
            directory: String?,
            sessionId: String,
        ): MessageSessionKey =
            MessageSessionKey(
                connectionProfileId = connectionProfileId,
                directory = directory.orEmpty(),
                sessionId = sessionId,
            )
    }

    /**
     * Opaque store slot that isolates profile + directory + session without
     * changing the MessageStore API surface.
     */
    val storeSlot: String
        get() = "$connectionProfileId\u001f$directory\u001f$sessionId"
}
