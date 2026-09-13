import Testing
@testable import OpenCode_Mobile

struct LiveOverallPolicyTests {
    @Test func firstFullyLiveSet_isConnectedWithoutReconnect() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(),
            input: LiveOverallInput(
                required: ["global/event"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(decision.connectionState == .connected)
        #expect(decision.publishReconnect == false)
        #expect(decision.flags.wasFullyConnected)
        #expect(decision.flags.lastAllLive)
    }

    @Test func allLiveAfterFullConnection_publishesReconnectOnce() {
        let dropped = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(
                lastAllLive: true,
                lastRequiredTargets: ["global/event"],
                lastLiveTargets: ["global/event"],
                wasFullyConnected: true
            ),
            input: LiveOverallInput(
                required: ["global/event"],
                live: [],
                pathLost: false
            )
        )
        #expect(dropped.connectionState == .reconnecting)
        #expect(dropped.publishReconnect == false)
        let recovered = LiveOverallPolicy.decide(
            flags: dropped.flags,
            input: LiveOverallInput(
                required: ["global/event"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(recovered.publishReconnect)
        let stillLive = LiveOverallPolicy.decide(
            flags: recovered.flags,
            input: LiveOverallInput(
                required: ["global/event"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(stillLive.publishReconnect == false)
    }

    @Test func occupancyResume_publishesReconnectWhenWasFullyConnected() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(wasFullyConnected: true),
            input: LiveOverallInput(
                required: ["api/event"],
                live: ["api/event"],
                pathLost: false
            )
        )
        #expect(decision.publishReconnect)
        #expect(decision.connectionState == .connected)
    }

    @Test func requiredGrowth_goesReconnectingThenReconnects() {
        let dropped = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(
                lastAllLive: true,
                lastRequiredTargets: ["global/event"],
                lastLiveTargets: ["global/event"],
                wasFullyConnected: true
            ),
            input: LiveOverallInput(
                required: ["global/event", "event:/tmp/a"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(dropped.connectionState == .reconnecting)
        let recovered = LiveOverallPolicy.decide(
            flags: dropped.flags,
            input: LiveOverallInput(
                required: ["global/event", "event:/tmp/a"],
                live: ["global/event", "event:/tmp/a"],
                pathLost: false
            )
        )
        #expect(recovered.connectionState == .connected)
        #expect(recovered.publishReconnect)
    }

    @Test func incumbentLeaseLost_isReconnectingWithoutReconnectEvent() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(
                lastAllLive: true,
                lastRequiredTargets: ["global/event", "event:/tmp/a"],
                lastLiveTargets: ["global/event", "event:/tmp/a"],
                wasFullyConnected: true
            ),
            input: LiveOverallInput(
                required: ["global/event", "event:/tmp/a"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(decision.connectionState == .reconnecting)
        #expect(decision.publishReconnect == false)
    }

    @Test func notYetConnected_staysConnecting() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(),
            input: LiveOverallInput(
                required: ["api/event"],
                live: [],
                pathLost: false
            )
        )
        #expect(decision.connectionState == .connecting)
        #expect(decision.publishReconnect == false)
    }
}
