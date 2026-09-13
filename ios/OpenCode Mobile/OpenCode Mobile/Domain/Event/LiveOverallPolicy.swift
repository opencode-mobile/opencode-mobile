import Foundation

nonisolated struct LiveOverallFlags: Equatable, Sendable {
    var lastAllLive = false
    var lastRequiredTargets: [String] = []
    var lastLiveTargets: Set<String> = []
    var wasFullyConnected = false
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
}

nonisolated enum LiveOverallPolicy {
    static func decide(flags: LiveOverallFlags, input: LiveOverallInput) -> LiveOverallDecision {
        let allLive = !input.required.isEmpty && input.required.allSatisfy { input.live.contains($0) }
        var next = flags
        next.lastAllLive = allLive
        next.lastRequiredTargets = input.required
        next.lastLiveTargets = input.live
        if !allLive {
            return LiveOverallDecision(
                flags: next,
                connectionState: flags.wasFullyConnected ? .reconnecting : .connecting,
                publishReconnect: false
            )
        }
        let publishReconnect = !flags.lastAllLive && flags.wasFullyConnected
        next.wasFullyConnected = true
        return LiveOverallDecision(
            flags: next,
            connectionState: .connected,
            publishReconnect: publishReconnect
        )
    }
}
