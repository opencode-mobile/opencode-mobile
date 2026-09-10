package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.ServerProtocol
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
    val hasEventSubscriber: Boolean
        get() = _events.subscriptionCount.value > 0

    private val gapClock = AtomicLong(0)
    private val republishClock = AtomicLong(0)
    private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
    override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()
    private var wasConnected = false
    private var occupying = false

    /** Same generation and gap, but a distinct StateFlow value so collectors wake. */
    fun republishSameGeneration() {
        _snapshot.update { current ->
            current.copy(
                liveTargets = current.liveTargets + "same-generation-${republishClock.incrementAndGet()}",
            )
        }
    }

    fun emit(event: OpencodeEvent) {
        _events.tryEmit(event)
    }

    fun applyConfig(
        profileId: String,
        protocol: ServerProtocol,
        directories: List<String>,
    ) {
        val config =
            LiveConnectionConfig(
                profileId = profileId,
                connectionIdentity = profileId,
                protocol = protocol,
                directories = directories,
            )
        val targets = SseSubscribeTargets.forProtocol(protocol, directories).map { it.key }
        _snapshot.update {
            it.copy(
                config = config,
                requiredTargets = targets,
                liveTargets = if (occupying) targets else emptyList(),
                connectionState = _connectionState.value,
            )
        }
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

    fun openNewerThan(generation: Long, reason: LiveGapReason = LiveGapReason.Reconnect) {
        do {
            openGap(reason)
        } while ((_snapshot.value.gap?.generation ?: 0L) <= generation)
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
