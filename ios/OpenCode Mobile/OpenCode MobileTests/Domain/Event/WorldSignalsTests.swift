import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct WorldSignalsTests {
    @Test func profileSwitched_marksStale() {
        #expect(WorldSignal.profileSwitched.marksStale)
        #expect(WorldSignal.backgroundStop.marksStale)
        #expect(WorldSignal.pathLost.marksStale)
        #expect(!WorldSignal.foreground.marksStale)
        #expect(!WorldSignal.background.marksStale)
    }

    @Test func canCatchUp_requiresActiveFullyLiveLeases() {
        let connected = LiveConnectionSnapshot(
            connectionState: .connected,
            requiredTargets: ["api/event"],
            liveTargets: ["api/event"]
        )
        let connecting = LiveConnectionSnapshot(
            connectionState: .connecting,
            requiredTargets: ["api/event"],
            liveTargets: []
        )
        let reconnecting = LiveConnectionSnapshot(
            connectionState: .reconnecting,
            requiredTargets: ["api/event"],
            liveTargets: []
        )
        let connectedWithoutLeases = LiveConnectionSnapshot(connectionState: .connected)
        let partialV1 = LiveConnectionSnapshot(
            connectionState: .connected,
            requiredTargets: ["global/event", "event:/repo"],
            liveTargets: ["global/event"]
        )
        let fullyLiveV1 = LiveConnectionSnapshot(
            connectionState: .connected,
            requiredTargets: ["global/event", "event:/repo"],
            liveTargets: ["global/event", "event:/repo"]
        )

        #expect(!canCatchUp(occupancy: .stopped, snapshot: connected))
        #expect(!canCatchUp(occupancy: .grace, snapshot: connected))
        #expect(!canCatchUp(occupancy: .active, snapshot: connecting))
        #expect(!canCatchUp(occupancy: .active, snapshot: reconnecting))
        #expect(!canCatchUp(occupancy: .active, snapshot: connectedWithoutLeases))
        #expect(!canCatchUp(occupancy: .active, snapshot: partialV1))
        #expect(!partialV1.isFullyLive)
        #expect(connected.isFullyLive)
        #expect(fullyLiveV1.isFullyLive)
        #expect(canCatchUp(occupancy: .active, snapshot: connected))
        #expect(canCatchUp(occupancy: .active, snapshot: fullyLiveV1))
    }
}
