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

    private val generationClock = AtomicLong(0)
    private val republishClock = AtomicLong(0)
    private val _snapshot = MutableStateFlow(LiveConnectionSnapshot())
    override val snapshot: StateFlow<LiveConnectionSnapshot> = _snapshot.asStateFlow()
    private var wasConnected = false
    private var occupying = false
    private var explicitLeaseModeling = false

    /**
     * Traces that emit `target.open` / `target.close` own live-target occupancy.
     * Other tests keep instant-live on Connected.
     */
    fun modelLeasesExplicitly() {
        explicitLeaseModeling = true
    }

    fun openTarget(target: String) {
        explicitLeaseModeling = true
        _snapshot.update { current ->
            current.copy(liveTargets = (current.liveTargets + target).distinct())
        }
    }

    fun closeTarget(target: String) {
        explicitLeaseModeling = true
        _snapshot.update { current ->
            current.copy(liveTargets = current.liveTargets.filterNot { it == target })
        }
    }

    /** Same generation, but a distinct StateFlow value so collectors wake. */
    fun setLiveTargets(targets: List<String>) {
        _snapshot.update { it.copy(liveTargets = targets) }
    }

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
                liveTargets = liveTargetsFor(it.liveTargets, targets, _connectionState.value),
                connectionState = _connectionState.value,
            )
        }
    }

    fun bumpGeneration() {
        val generation = generationClock.incrementAndGet()
        _snapshot.update {
            it.copy(
                generation = generation,
                connectionState = _connectionState.value,
            )
        }
    }

    fun openGap() {
        bumpGeneration()
        emit(OpencodeEvent(type = "sse.parse.failed"))
    }

    fun setConnectionState(state: EventStreamConnectionState) {
        _connectionState.value = state
        occupying =
            state != EventStreamConnectionState.Idle &&
            state != EventStreamConnectionState.Disconnected
        applyLeaseOccupancy(state)
        if (state == EventStreamConnectionState.Connected) {
            wasConnected = true
        }
        if (state == EventStreamConnectionState.Idle) {
            wasConnected = false
        }
    }

    private fun applyLeaseOccupancy(state: EventStreamConnectionState) {
        _snapshot.update { current ->
            val required =
                current.requiredTargets.ifEmpty {
                    if (state == EventStreamConnectionState.Connected ||
                        state == EventStreamConnectionState.Connecting ||
                        state == EventStreamConnectionState.Reconnecting
                    ) {
                        listOf("api/event")
                    } else {
                        emptyList()
                    }
                }
            current.copy(
                connectionState = state,
                requiredTargets = required,
                liveTargets = liveTargetsFor(current.liveTargets, required, state),
            )
        }
    }

    private fun liveTargetsFor(
        current: List<String>,
        required: List<String>,
        state: EventStreamConnectionState,
    ): List<String> =
        when {
            state == EventStreamConnectionState.Idle ||
                state == EventStreamConnectionState.Disconnected -> emptyList()
            explicitLeaseModeling -> current
            state == EventStreamConnectionState.Connected -> required
            else -> emptyList()
        }

    override fun start() {
        if (occupying || _connectionState.value == EventStreamConnectionState.Connected) {
            occupying = true
            return
        }
        occupying = true
        bumpGeneration()
        wasConnected = false
        setConnectionState(EventStreamConnectionState.Connected)
    }

    override fun stop() {
        occupying = false
        wasConnected = false
        _connectionState.value = EventStreamConnectionState.Idle
        bumpGeneration()
        applyLeaseOccupancy(EventStreamConnectionState.Idle)
    }
}
