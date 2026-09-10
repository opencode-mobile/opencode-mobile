package dev.opencode.mobile.domain.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

class FakeForegroundEventStream : LiveConnection {
    private val _connectionState = MutableStateFlow(EventStreamConnectionState.Idle)
    override val connectionState: StateFlow<EventStreamConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<OpencodeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

    private val gapClock = AtomicLong(0)
    private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
    override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()
    private var wasConnected = false
    private var occupying = false

    fun emit(event: OpencodeEvent) {
        _events.tryEmit(event)
    }

    fun openGap(reason: LiveGapReason = LiveGapReason.Reconnect) {
        val generation = gapClock.incrementAndGet()
        _snapshot.update {
            it.copy(
                generation = generation,
                gap = LiveGapRevision(generation, reason),
                connectionState = _connectionState.value,
            )
        }
    }

    fun setConnectionState(state: EventStreamConnectionState) {
        val previous = _connectionState.value
        _connectionState.value = state
        if (wasConnected &&
            (
                state == EventStreamConnectionState.Idle ||
                    state == EventStreamConnectionState.Disconnected ||
                    state == EventStreamConnectionState.Reconnecting
            )
        ) {
            openGap(LiveGapReason.Reconnect)
        } else {
            _snapshot.update { it.copy(connectionState = state) }
        }
        if (state == EventStreamConnectionState.Connected) {
            wasConnected = true
            occupying = true
        }
        if (state == EventStreamConnectionState.Idle) {
            wasConnected = false
        }
        if (previous == state &&
            state != EventStreamConnectionState.Idle &&
            state != EventStreamConnectionState.Disconnected &&
            state != EventStreamConnectionState.Reconnecting
        ) {
            return
        }
    }

    override fun start() {
        if (occupying || _connectionState.value == EventStreamConnectionState.Connected) {
            occupying = true
            return
        }
        occupying = true
        openGap(LiveGapReason.OccupancyStart)
        wasConnected = false
        setConnectionState(EventStreamConnectionState.Connected)
    }

    override fun stop() {
        occupying = false
        _connectionState.value = EventStreamConnectionState.Idle
        wasConnected = false
        openGap(LiveGapReason.Stopped)
        _snapshot.update { it.copy(connectionState = EventStreamConnectionState.Idle) }
    }
}
