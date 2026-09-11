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
        #expect(decision.openGap == nil)
        #expect(decision.flags.wasFullyConnected)
        #expect(decision.flags.occupyingConnected)
        #expect(decision.flags.lastAllLive)
    }

    @Test func allLiveAfterFullConnection_publishesReconnectOnce() {
        let first = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(wasFullyConnected: true),
            input: LiveOverallInput(
                required: ["global/event"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(first.publishReconnect)
        #expect(first.flags.emittedReconnectForCurrentGap)
        let second = LiveOverallPolicy.decide(
            flags: first.flags,
            input: LiveOverallInput(
                required: ["global/event"],
                live: ["global/event"],
                pathLost: false
            )
        )
        #expect(second.publishReconnect == false)
    }

    @Test func coldResumeBarrier_publishesReconnectEvenIfNeverFullyConnected() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(pendingResumeBarrier: true),
            input: LiveOverallInput(
                required: ["api/event"],
                live: ["api/event"],
                pathLost: false
            )
        )
        #expect(decision.publishReconnect)
        #expect(decision.flags.pendingResumeBarrier == false)
    }

    @Test func requiredGrowth_doesNotOpenGapOrReconnect() {
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
        #expect(dropped.openGap == nil)
        #expect(dropped.flags.suppressReconnectForRequiredGrowth)
        let recovered = LiveOverallPolicy.decide(
            flags: dropped.flags,
            input: LiveOverallInput(
                required: ["global/event", "event:/tmp/a"],
                live: ["global/event", "event:/tmp/a"],
                pathLost: false
            )
        )
        #expect(recovered.connectionState == .connected)
        #expect(recovered.publishReconnect == false)
        #expect(recovered.flags.suppressReconnectForRequiredGrowth == false)
    }

    @Test func incumbentLeaseLost_opensLeaseLostGap() {
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
        #expect(decision.openGap == .leaseLost)
        #expect(decision.connectionState == .reconnecting)
        #expect(decision.publishReconnect == false)
    }

    @Test func incumbentLostWhilePathUnavailable_opensPathLostGap() {
        let decision = LiveOverallPolicy.decide(
            flags: LiveOverallFlags(
                lastRequiredTargets: ["api/event"],
                lastLiveTargets: ["api/event"],
                wasFullyConnected: true
            ),
            input: LiveOverallInput(
                required: ["api/event"],
                live: [],
                pathLost: true
            )
        )
        #expect(decision.openGap == .pathLost)
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
        #expect(decision.openGap == nil)
        #expect(decision.publishReconnect == false)
    }
}
