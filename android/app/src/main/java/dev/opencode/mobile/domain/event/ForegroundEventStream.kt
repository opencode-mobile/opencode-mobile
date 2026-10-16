package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ForegroundEventStream {
    val connectionState: StateFlow<EventStreamConnectionState>
    val events: SharedFlow<OpencodeEvent>

    fun start()

    suspend fun stop()

    fun onReconnectWake() {}
}

/** No events. View-model tests use this when they do not pass a stream. */
object IdleForegroundEventStream : ForegroundEventStream {
    override val connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
    override val events = MutableSharedFlow<OpencodeEvent>()

    override fun start() = Unit

    override suspend fun stop() = Unit
}
