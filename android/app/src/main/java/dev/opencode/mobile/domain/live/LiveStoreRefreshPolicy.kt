package dev.opencode.mobile.domain.live

internal object LiveStoreRefreshPolicy {
    const val MESSAGE_DEBOUNCE_MS = 300L
    const val SESSION_DEBOUNCE_MS = 500L
    const val STATUS_DEBOUNCE_MS = 300L
    const val WORKING_POLL_INTERVAL_MS = 10_000L
    const val SUBSCRIBER_STOP_TIMEOUT_MS = 5_000L

    /**
     * Ceiling for [StoreInvalidationBus.invalidateAndWait]. Chosen as a UX ceiling
     * below the 60s Ktor request timeout: the ack only gates a pull-to-refresh
     * spinner, and a minute of spinning on a stalled radio is worse than dropping
     * the spinner while the request finishes in the background. The store still
     * emits when the slow response lands, so the list catches up either way.
     */
    const val INVALIDATION_ACK_TIMEOUT_MS = 15_000L
}

internal data class MessageObservationKey(
    val sessionId: String,
    val directory: String,
)
