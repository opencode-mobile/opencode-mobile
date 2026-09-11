package dev.opencode.mobile.domain.message

/** Closed-loop observation health for MessageSync recovery. */
sealed interface MessageSyncHealth {
    data object Healthy : MessageSyncHealth

    data class Recovering(
        val reason: MessageSyncRecoveryReason,
    ) : MessageSyncHealth

    data class Stale(
        val reason: MessageSyncRecoveryReason,
    ) : MessageSyncHealth

    val isHealthy: Boolean
        get() = this is Healthy

    val requiresAuthoritativeRecovery: Boolean
        get() = this is Recovering

    /** Session Chat Syncing pill. Overflow and raced authority stay quiet. */
    val showsCatchUpChrome: Boolean
        get() =
            when (this) {
                Healthy -> false
                is Stale -> true
                is Recovering ->
                    reason != MessageSyncRecoveryReason.Overflow &&
                        reason != MessageSyncRecoveryReason.RacedAuthority
            }
}

/** REST authority-window result. Discarded means live local state won the race. */
enum class AuthorityWindowOutcome {
    Accepted,
    Discarded,
    Failed,
}

enum class MessageSyncRecoveryReason {
    Overflow,
    Reconnect,
    ReloadFailed,
    RacedAuthority,
    TransportUnavailable,
}

/** Complete ownership key for observation, recovery, fingerprints, and store slots. */
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

object MessageSyncRecoveryPolicy {
    const val INITIAL_BACKOFF_MS = 250L
    const val MAX_BACKOFF_MS = 5_000L
}
