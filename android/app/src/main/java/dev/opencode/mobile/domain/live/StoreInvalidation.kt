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
