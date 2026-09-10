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

    @Test func canCatchUp_requiresActiveConnectedOccupancy() {
        let connected = LiveConnectionSnapshot(connectionState: .connected)
        let connecting = LiveConnectionSnapshot(connectionState: .connecting)
        let reconnecting = LiveConnectionSnapshot(connectionState: .reconnecting)

        #expect(!canCatchUp(occupancy: .stopped, snapshot: connected))
        #expect(!canCatchUp(occupancy: .grace, snapshot: connected))
        #expect(!canCatchUp(occupancy: .active, snapshot: connecting))
        #expect(canCatchUp(occupancy: .active, snapshot: connected))
        #expect(canCatchUp(occupancy: .active, snapshot: reconnecting))
    }
}
