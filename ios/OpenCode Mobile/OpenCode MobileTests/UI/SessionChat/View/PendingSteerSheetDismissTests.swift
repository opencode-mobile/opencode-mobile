import Testing
@testable import OpenCode_Mobile

struct PendingSteerSheetDismissTests {
    @Test func keepsSheetWhenTargetIsStillSteerPending() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: "msg-1",
                timeline: .ready,
                messages: [row(id: "msg-1", pendingDelivery: .steer)]
            ) == false
        )
    }

    @Test func dismissesWhenAgentPicksUpPrompt() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: "msg-1",
                timeline: .ready,
                messages: [row(id: "msg-1", pendingDelivery: nil)]
            )
        )
    }

    @Test func dismissesWhenRowLeavesTimeline() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: "msg-1",
                timeline: .ready,
                messages: [row(id: "other", pendingDelivery: .steer)]
            )
        )
    }

    @Test func dismissesWhenLoadedTimelineIsEmpty() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: "msg-1",
                timeline: .empty,
                messages: []
            )
        )
    }

    @Test func doesNotDismissDuringInitialLoad() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: "msg-1",
                timeline: .loading,
                messages: []
            ) == false
        )
    }

    @Test func doesNotDismissWhenNoTarget() {
        #expect(
            shouldDismissPendingSteerSheet(
                targetId: nil,
                timeline: .ready,
                messages: []
            ) == false
        )
    }

    private func row(id: String, pendingDelivery: FollowUpDelivery?) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: .user,
            text: "prompt",
            metadataLabel: "user",
            pendingDelivery: pendingDelivery
        )
    }
}
