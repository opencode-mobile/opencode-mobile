package dev.opencode.mobile.domain.live

internal object LiveStoreRefreshPolicy {
    const val MESSAGE_DEBOUNCE_MS = 300L
    const val SESSION_DEBOUNCE_MS = 500L
    const val STATUS_DEBOUNCE_MS = 300L
    const val WORKING_POLL_INTERVAL_MS = 10_000L
    const val STATUS_RUNNING_RECONCILE_MS = 30_000L
    const val SUBSCRIBER_STOP_TIMEOUT_MS = 5_000L
    const val INVALIDATION_ACK_TIMEOUT_MS = 15_000L
}

internal data class MessageObservationKey(
    val sessionId: String,
    val directory: String,
)
