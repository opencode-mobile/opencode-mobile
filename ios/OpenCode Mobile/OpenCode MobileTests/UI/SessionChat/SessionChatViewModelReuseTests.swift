import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatViewModelReuseTests {
    @Test func firstAppearanceBuildsViewModel() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: nil,
            hasViewModel: false
        )

        #expect(decision == .rebuild)
    }

    @Test func reappearForSameSessionKeepsViewModel() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: "ses-1",
            hasViewModel: true
        )

        #expect(decision == .reuse)
    }

    @Test func differentSessionRebuilds() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-2",
            boundTaskID: "ses-1",
            hasViewModel: true
        )

        #expect(decision == .rebuild)
    }

    @Test func newOptimisticInstanceRebuilds() {
        let placeholder = OptimisticSessionCreate.placeholderSessionID
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "\(placeholder)-\(UUID().uuidString)",
            boundTaskID: "\(placeholder)-\(UUID().uuidString)",
            hasViewModel: true
        )

        #expect(decision == .rebuild)
    }

    @Test func missingViewModelRebuildsEvenWhenIDMatches() {
        let decision = SessionChatViewModelReuse.decision(
            currentTaskID: "ses-1",
            boundTaskID: "ses-1",
            hasViewModel: false
        )

        #expect(decision == .rebuild)
    }
}

struct SessionChatHostLeasesTests {
    @Test func firstHostStartsAndLastHostStops() {
        var leases = SessionChatHostLeases()
        let host = UUID()

        let starts = leases.attach(host)
        let stops = leases.detach(host)

        #expect(starts)
        #expect(stops)
        #expect(leases.isEmpty)
    }

    @Test func handOffKeepsObservingWhenOutgoingLeavesLast() {
        // Incoming chat appears before the outgoing chat's disappear lands.
        var leases = SessionChatHostLeases()
        let outgoing = UUID()
        let incoming = UUID()
        _ = leases.attach(outgoing)

        let incomingStarts = leases.attach(incoming)
        let outgoingStops = leases.detach(outgoing)
        let incomingStops = leases.detach(incoming)

        #expect(!incomingStarts)
        #expect(!outgoingStops)
        #expect(incomingStops)
    }

    @Test func handOffRestartsWhenOutgoingLeavesFirst() {
        var leases = SessionChatHostLeases()
        let outgoing = UUID()
        let incoming = UUID()
        _ = leases.attach(outgoing)

        let outgoingStops = leases.detach(outgoing)
        let incomingStarts = leases.attach(incoming)

        #expect(outgoingStops)
        #expect(incomingStarts)
    }

    @Test func repeatedAttachIsOneLease() {
        var leases = SessionChatHostLeases()
        let host = UUID()
        _ = leases.attach(host)

        let startsAgain = leases.attach(host)
        let stops = leases.detach(host)

        #expect(!startsAgain)
        #expect(stops)
    }

    @Test func detachWithoutAttachDoesNothing() {
        var leases = SessionChatHostLeases()
        _ = leases.attach(UUID())

        let stops = leases.detach(UUID())

        #expect(!stops)
        #expect(!leases.isEmpty)
    }
}
