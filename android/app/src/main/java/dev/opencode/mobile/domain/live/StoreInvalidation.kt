package dev.opencode.mobile.domain.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

data class StoreInvalidate(
    val key: String?,
    val reason: String,
    val ack: CompletableDeferred<Unit>? = null,
)

/**
 * @param ackTimeoutMs ceiling for [invalidateAndWait]. Injectable so tests can bound
 *   the wait without waiting on the production ceiling.
 */
class StoreInvalidationBus(
    private val ackTimeoutMs: Long = LiveStoreRefreshPolicy.INVALIDATION_ACK_TIMEOUT_MS,
) {
    private val _events = MutableSharedFlow<StoreInvalidate>(extraBufferCapacity = 32)
    val events: SharedFlow<StoreInvalidate> = _events.asSharedFlow()

    fun invalidate(
        key: String?,
        reason: String,
    ) {
        _events.tryEmit(StoreInvalidate(key = key, reason = reason))
    }

    /**
     * Emits an invalidation and waits for a collector to acknowledge it.
     *
     * The zero-subscriber check is only a fast path: a subscriber can disappear
     * between the check and the emit, and a collector can be cancelled after
     * receiving the event, so the wait is always bounded. Returning here means
     * "stop waiting", never "the store now holds fresh data" — callers must not
     * read it as a freshness guarantee.
     */
    suspend fun invalidateAndWait(
        key: String?,
        reason: String,
    ) {
        if (_events.subscriptionCount.value == 0) return
        val ack = CompletableDeferred<Unit>()
        withTimeoutOrNull(ackTimeoutMs) {
            _events.emit(StoreInvalidate(key = key, reason = reason, ack = ack))
            ack.await()
        }
    }
}
