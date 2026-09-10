package dev.opencode.mobile.domain.event

import dev.opencode.mobile.domain.connection.ServerProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveConnectionConfig(
    val profileId: String,
    val connectionIdentity: String,
    val protocol: ServerProtocol,
    val directories: List<String>,
) {
    data class Identity(
        val profileId: String,
        val connectionIdentity: String,
        val protocol: ServerProtocol,
    )

    val identity: Identity
        get() = Identity(profileId, connectionIdentity, protocol)
}

enum class LiveGapReason {
    OccupancyStart,
    Reconnect,
    ConfigChanged,
    Stopped,
    PathLost,
    LeaseLost,
    ParseFailed,
}

data class LiveGapRevision(
    val generation: Long,
    val reason: LiveGapReason,
)

data class LiveConnectionSnapshot(
    val config: LiveConnectionConfig? = null,
    val generation: Long = 0,
    val connectionState: EventStreamConnectionState = EventStreamConnectionState.Idle,
    val gap: LiveGapRevision? = null,
    val requiredTargets: List<String> = emptyList(),
    val liveTargets: List<String> = emptyList(),
)

/**
 * Transport authority for foreground SSE. [ForegroundEventStream] stays the
 * caller-facing occupancy seam; this snapshot/gap surface is what later
 * phases query instead of inventing another clock.
 */
interface LiveConnection : ForegroundEventStream {
    val snapshot: StateFlow<LiveConnectionSnapshot>
}

fun LiveConnectionSnapshot.isReachable(): Boolean =
    connectionState == EventStreamConnectionState.Connecting ||
        connectionState == EventStreamConnectionState.Connected ||
        connectionState == EventStreamConnectionState.Reconnecting

fun ForegroundEventStream.asLiveConnection(): LiveConnection =
    this as? LiveConnection ?: AdaptedLiveConnection(this)

private class AdaptedLiveConnection(
    private val stream: ForegroundEventStream,
) : LiveConnection,
    ForegroundEventStream by stream {
    override val snapshot: StateFlow<LiveConnectionSnapshot> =
        MutableStateFlow(LiveConnectionSnapshot(connectionState = stream.connectionState.value))
}
