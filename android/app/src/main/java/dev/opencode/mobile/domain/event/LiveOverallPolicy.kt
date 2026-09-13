package dev.opencode.mobile.domain.event

internal data class LiveOverallFlags(
    val wasFullyConnected: Boolean = false,
)

internal data class LiveOverallInput(
    val required: List<String>,
    val live: Set<String>,
)

internal data class LiveOverallDecision(
    val flags: LiveOverallFlags,
    val connectionState: EventStreamConnectionState,
    val publishReconnect: Boolean,
)

internal object LiveOverallPolicy {
    fun decide(
        flags: LiveOverallFlags,
        input: LiveOverallInput,
        currentlyConnected: Boolean = false,
    ): LiveOverallDecision {
        val allLive = input.required.isNotEmpty() && input.required.all { it in input.live }
        if (!allLive) {
            return LiveOverallDecision(
                flags = flags,
                connectionState =
                    if (flags.wasFullyConnected) {
                        EventStreamConnectionState.Reconnecting
                    } else {
                        EventStreamConnectionState.Connecting
                    },
                publishReconnect = false,
            )
        }
        return LiveOverallDecision(
            flags = flags.copy(wasFullyConnected = true),
            connectionState = EventStreamConnectionState.Connected,
            publishReconnect = !currentlyConnected,
        )
    }
}
