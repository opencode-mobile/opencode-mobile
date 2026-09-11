import Foundation

nonisolated struct LiveOverallFlags: Equatable, Sendable {
    var lastAllLive = false
    var lastRequiredTargets: [String] = []
    var lastLiveTargets: Set<String> = []
    var wasFullyConnected = false
    var occupyingConnected = false
    var pendingResumeBarrier = false
    var emittedReconnectForCurrentGap = false
    var suppressReconnectForRequiredGrowth = false
}

nonisolated struct LiveOverallInput: Equatable, Sendable {
    var required: [String]
    var live: Set<String>
    var pathLost: Bool
}

nonisolated struct LiveOverallDecision: Equatable, Sendable {
    var flags: LiveOverallFlags
    var connectionState: EventStreamConnectionState
    var publishReconnect: Bool
    var openGap: LiveGapReason?
}

nonisolated enum LiveOverallPolicy {
    static func decide(flags: LiveOverallFlags, input: LiveOverallInput) -> LiveOverallDecision {
        let observed = observe(flags: flags, input: input)
        let classified = classify(flags: flags, observed: observed, pathLost: input.pathLost)
        let connected = chrome(flags: classified.flags, allLive: observed.allLive)
        var next = connected.flags
        next.lastAllLive = observed.allLive
        next.lastRequiredTargets = input.required
        next.lastLiveTargets = input.live
        return LiveOverallDecision(
            flags: next,
            connectionState: connected.state,
            publishReconnect: connected.publishReconnect,
            openGap: classified.openGap
        )
    }

    private struct Observation {
        var allLive: Bool
        var incumbentLost: Bool
        var requiredGrew: Bool
        var lostLive: Bool
    }

    private struct Classified {
        var flags: LiveOverallFlags
        var openGap: LiveGapReason?
    }

    private struct Chrome {
        var flags: LiveOverallFlags
        var state: EventStreamConnectionState
        var publishReconnect: Bool
    }

    private static func observe(flags: LiveOverallFlags, input: LiveOverallInput) -> Observation {
        let allLive = !input.required.isEmpty && input.required.allSatisfy { input.live.contains($0) }
        return Observation(
            allLive: allLive,
            incumbentLost: flags.lastRequiredTargets.contains { key in
                input.required.contains(key) && flags.lastLiveTargets.contains(key) && !input.live.contains(key)
            },
            requiredGrew: !flags.lastRequiredTargets.isEmpty
                && flags.lastRequiredTargets.allSatisfy { input.required.contains($0) }
                && input.required.count > flags.lastRequiredTargets.count,
            lostLive: flags.lastAllLive && !allLive
        )
    }

    /// Gap if an incumbent died; suppress reconnect if the required set only grew.
    private static func classify(
        flags: LiveOverallFlags,
        observed: Observation,
        pathLost: Bool
    ) -> Classified {
        if observed.incumbentLost {
            var next = flags
            next.suppressReconnectForRequiredGrowth = false
            next.emittedReconnectForCurrentGap = false
            return Classified(flags: next, openGap: pathLost ? .pathLost : .leaseLost)
        }
        if observed.lostLive && pathLost {
            var next = flags
            next.emittedReconnectForCurrentGap = false
            return Classified(flags: next, openGap: .pathLost)
        }
        if observed.lostLive && observed.requiredGrew {
            var next = flags
            next.suppressReconnectForRequiredGrowth = true
            return Classified(flags: next, openGap: nil)
        }
        return Classified(flags: flags, openGap: nil)
    }

    private static func chrome(flags: LiveOverallFlags, allLive: Bool) -> Chrome {
        if !allLive {
            return Chrome(
                flags: flags,
                state: flags.wasFullyConnected ? .reconnecting : .connecting,
                publishReconnect: false
            )
        }
        let publishReconnect = (flags.wasFullyConnected || flags.pendingResumeBarrier)
            && !flags.emittedReconnectForCurrentGap
            && !flags.suppressReconnectForRequiredGrowth
        var next = flags
        next.occupyingConnected = true
        next.wasFullyConnected = true
        next.suppressReconnectForRequiredGrowth = false
        if publishReconnect {
            next.emittedReconnectForCurrentGap = true
            next.pendingResumeBarrier = false
        }
        return Chrome(flags: next, state: .connected, publishReconnect: publishReconnect)
    }
}
