import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite
struct GapAuthorityTests {
    @Test func lateSubscriberSeesOpenGap() {
        let authority = GapAuthority()
        let snapshot = LiveConnectionSnapshot(
            connectionState: .connecting,
            gap: LiveGapRevision(generation: 3, reason: .stopped)
        )
        #expect(authority.needsReconcile(snapshot))
        #expect(authority.tryBegin(snapshot) == 3)
    }

    @Test func singleFlightBlocksDuplicateBegin() {
        let authority = GapAuthority()
        let snapshot = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 2, reason: .reconnect)
        )
        #expect(authority.tryBegin(snapshot) == 2)
        #expect(authority.tryBegin(snapshot) == nil)
        #expect(!authority.needsReconcile(snapshot))
    }

    @Test func failedReloadRemainsBehind() {
        let authority = GapAuthority()
        let snapshot = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 4, reason: .parseFailed)
        )
        #expect(authority.tryBegin(snapshot) == 4)
        authority.fail(4)
        #expect(authority.needsReconcile(snapshot))
        #expect(authority.tryBegin(snapshot) == 4)
    }

    @Test func acknowledgeClearsObligation() {
        let authority = GapAuthority()
        let snapshot = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 1, reason: .occupancyStart)
        )
        authority.acknowledgeCurrent(snapshot)
        #expect(!authority.needsReconcile(snapshot))
        #expect(authority.tryBegin(snapshot) == nil)
    }

    @Test func acknowledgeCurrent_whileOlderInFlight_pinsInFlightAndSuppressesReconcile() {
        let authority = GapAuthority()
        let begun = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 3, reason: .reconnect)
        )
        #expect(authority.tryBegin(begun) == 3)
        let newer = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 4, reason: .reconnect)
        )
        authority.acknowledgeCurrent(newer)
        #expect(authority.acknowledgedGeneration() == 4)
        #expect(authority.inFlightGeneration() == 3)
        #expect(!authority.needsReconcile(newer))
        #expect(authority.tryBegin(newer) == nil)
    }

    @Test func captureBegin_doesNotClobberExistingInFlight() {
        let authority = GapAuthority()
        let snapshot = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 5, reason: .reconnect)
        )
        #expect(authority.tryBegin(snapshot) == 5)
        #expect(authority.captureBegin(snapshot) == 5)
        #expect(authority.inFlightGeneration() == 5)
        authority.acknowledge(5)
        #expect(authority.inFlightGeneration() == nil)
        #expect(!authority.needsReconcile(snapshot))
    }

    @Test func captureBegin_takesNewerObligation() {
        let authority = GapAuthority()
        let begun = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 5, reason: .reconnect)
        )
        #expect(authority.tryBegin(begun) == 5)
        let newer = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 6, reason: .reconnect)
        )
        #expect(authority.captureBegin(newer) == 6)
        #expect(authority.inFlightGeneration() == 6)
        authority.acknowledge(5)
        #expect(authority.acknowledgedGeneration() == 5)
        #expect(authority.inFlightGeneration() == 6)
        #expect(!authority.needsReconcile(newer))
        authority.fail(6)
        #expect(authority.needsReconcile(newer))
    }

    @Test func acknowledge_exactInFlight_clearsAndLeavesNewerUnpaid() {
        let authority = GapAuthority()
        let begun = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 3, reason: .reconnect)
        )
        #expect(authority.tryBegin(begun) == 3)
        let newer = LiveConnectionSnapshot(
            connectionState: .connected,
            gap: LiveGapRevision(generation: 4, reason: .reconnect)
        )
        authority.acknowledge(3)
        #expect(authority.acknowledgedGeneration() == 3)
        #expect(authority.inFlightGeneration() == nil)
        #expect(authority.needsReconcile(newer))
        #expect(authority.tryBegin(newer) == 4)
    }
}
