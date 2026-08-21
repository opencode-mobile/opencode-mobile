package dev.opencode.mobile.domain.live

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class StoreInvalidate(
    val key: String?,
    val reason: String,
)

class StoreInvalidationBus {
    private val _events = MutableSharedFlow<StoreInvalidate>(extraBufferCapacity = 32)
    val events: SharedFlow<StoreInvalidate> = _events.asSharedFlow()

    fun invalidate(
        key: String?,
        reason: String,
    ) {
        _events.tryEmit(StoreInvalidate(key = key, reason = reason))
    }
}
