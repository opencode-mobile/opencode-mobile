package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cuts a reconnect backoff short. Wakes are counted rather than broadcast, so a wake that lands
 * before the waiter starts listening is still seen.
 */
internal class ReconnectWakeSignal {
    private val wakeCount = MutableStateFlow(0L)

    fun current(): Long = wakeCount.value

    fun wake() {
        wakeCount.update { it + 1 }
    }

    /** Returns true once a wake newer than [seen] exists, or false after [timeoutMs]. */
    suspend fun awaitAfter(
        seen: Long,
        timeoutMs: Long,
    ): Boolean {
        if (timeoutMs <= 0L) return false
        return withTimeoutOrNull(timeoutMs) {
            wakeCount.first { it != seen }
            true
        } == true
    }
}
