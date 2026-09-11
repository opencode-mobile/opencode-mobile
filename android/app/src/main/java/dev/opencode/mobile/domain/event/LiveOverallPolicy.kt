package dev.opencode.mobile.domain.event

internal data class LiveOverallFlags(
    val lastAllLive: Boolean = false,
    val lastRequiredTargets: List<String> = emptyList(),
    val lastLiveTargets: Set<String> = emptySet(),
    val wasFullyConnected: Boolean = false,
    val occupyingConnected: Boolean = false,
    val pendingResumeBarrier: Boolean = false,
    val emittedReconnectForCurrentGap: Boolean = false,
    val suppressReconnectForRequiredGrowth: Boolean = false,
)

internal data class LiveOverallInput(
    val required: List<String>,
    val live: Set<String>,
    val pathLost: Boolean,
)

internal data class LiveOverallDecision(
    val flags: LiveOverallFlags,
    val connectionState: EventStreamConnectionState,
    val publishReconnect: Boolean,
    val openGap: LiveGapReason?,
)

internal object LiveOverallPolicy {
    fun decide(
        flags: LiveOverallFlags,
        input: LiveOverallInput,
    ): LiveOverallDecision {
        val observed = observe(flags, input)
        val classified = classify(flags, observed, input.pathLost)
        val connected = chrome(classified.flags, observed.allLive)
        return LiveOverallDecision(
            flags =
                connected.flags.copy(
                    lastAllLive = observed.allLive,
                    lastRequiredTargets = input.required,
                    lastLiveTargets = input.live,
                ),
            connectionState = connected.state,
            publishReconnect = connected.publishReconnect,
            openGap = classified.openGap,
        )
    }

    private data class Observation(
        val allLive: Boolean,
        val incumbentLost: Boolean,
        val requiredGrew: Boolean,
        val lostLive: Boolean,
    )

    private data class Classified(
        val flags: LiveOverallFlags,
        val openGap: LiveGapReason?,
    )

    private data class Chrome(
        val flags: LiveOverallFlags,
        val state: EventStreamConnectionState,
        val publishReconnect: Boolean,
    )

    private fun observe(
        flags: LiveOverallFlags,
        input: LiveOverallInput,
    ): Observation {
        val allLive = input.required.isNotEmpty() && input.required.all { it in input.live }
        return Observation(
            allLive = allLive,
            incumbentLost =
                flags.lastRequiredTargets.any { key ->
                    key in input.required && key in flags.lastLiveTargets && key !in input.live
                },
            requiredGrew =
                flags.lastRequiredTargets.isNotEmpty() &&
                    flags.lastRequiredTargets.all { it in input.required } &&
                    input.required.size > flags.lastRequiredTargets.size,
            lostLive = flags.lastAllLive && !allLive,
        )
    }

    /** Gap if an incumbent died; suppress reconnect if the required set only grew. */
    private fun classify(
        flags: LiveOverallFlags,
        observed: Observation,
        pathLost: Boolean,
    ): Classified =
        when {
            observed.incumbentLost ->
                Classified(
                    flags =
                        flags.copy(
                            suppressReconnectForRequiredGrowth = false,
                            emittedReconnectForCurrentGap = false,
                        ),
                    openGap = if (pathLost) LiveGapReason.PathLost else LiveGapReason.LeaseLost,
                )
            observed.lostLive && pathLost ->
                Classified(
                    flags = flags.copy(emittedReconnectForCurrentGap = false),
                    openGap = LiveGapReason.PathLost,
                )
            observed.lostLive && observed.requiredGrew ->
                Classified(
                    flags = flags.copy(suppressReconnectForRequiredGrowth = true),
                    openGap = null,
                )
            else -> Classified(flags = flags, openGap = null)
        }

    private fun chrome(
        flags: LiveOverallFlags,
        allLive: Boolean,
    ): Chrome {
        if (!allLive) {
            return Chrome(
                flags = flags,
                state =
                    if (flags.wasFullyConnected) {
                        EventStreamConnectionState.Reconnecting
                    } else {
                        EventStreamConnectionState.Connecting
                    },
                publishReconnect = false,
            )
        }
        val publishReconnect =
            (flags.wasFullyConnected || flags.pendingResumeBarrier) &&
                !flags.emittedReconnectForCurrentGap &&
                !flags.suppressReconnectForRequiredGrowth
        return Chrome(
            flags =
                flags.copy(
                    occupyingConnected = true,
                    wasFullyConnected = true,
                    suppressReconnectForRequiredGrowth = false,
                    emittedReconnectForCurrentGap = flags.emittedReconnectForCurrentGap || publishReconnect,
                    pendingResumeBarrier = if (publishReconnect) false else flags.pendingResumeBarrier,
                ),
            state = EventStreamConnectionState.Connected,
            publishReconnect = publishReconnect,
        )
    }
}
