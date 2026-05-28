package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeForegroundEventStream : ForegroundEventStream {
    private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
    override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

    fun emit(event: OpencodeEvent) {
        _events.tryEmit(event)
    }

    override fun start() {
        _connectionState.value = EventStreamConnectionState.Connected
    }

    override fun stop() {
        _connectionState.value = EventStreamConnectionState.Idle
    }
}
