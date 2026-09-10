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
}
