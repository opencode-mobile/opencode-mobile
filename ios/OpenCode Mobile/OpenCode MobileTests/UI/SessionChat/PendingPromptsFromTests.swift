import Foundation
import Testing
@testable import OpenCode_Mobile

@MainActor
struct PendingPromptsFromTests {
    @Test func optimisticAttachmentOnlyBusySend_isSettling() {
        let prompts = SessionChatViewModel.pendingPrompts(
            loaded: [],
            optimistic: [
                SessionChatRow(
                    id: "pending-file",
                    role: .user,
                    text: "",
                    metadataLabel: "",
                    pendingDelivery: .steer,
                    isPending: true
                ),
            ]
        )
        #expect(prompts.count == 1)
        #expect(prompts[0].id == "pending-file")
        #expect(prompts[0].text == "")
        #expect(prompts[0].isSettling)
    }

    @Test func optimisticBusySend_isSettling() {
        let prompts = SessionChatViewModel.pendingPrompts(
            loaded: [],
            optimistic: [optimistic("pending-1", "Hold this", .steer)]
        )
        let prompt = prompts[0]
        #expect(prompts.count == 1)
        #expect(prompt.id == "pending-1")
        #expect(prompt.text == "Hold this")
        #expect(prompt.delivery == .steer)
        #expect(prompt.isSettling)
    }

    @Test func sameIdEcho_replacesSettlingRow() {
        let prompts = SessionChatViewModel.pendingPrompts(
            loaded: [inbox("pending-1", "Hold this", .steer)],
            optimistic: [optimistic("pending-1", "Hold this", .steer)]
        )
        let prompt = prompts[0]
        #expect(prompts.count == 1)
        #expect(prompt.id == "pending-1")
        #expect(!prompt.isSettling)
    }

    @Test func unionOrdersSteerBeforeQueue_serverThenSettling() {
        let prompts = SessionChatViewModel.pendingPrompts(
            loaded: [
                inbox("q-server", "Queued on server", .queue),
                inbox("s-server", "Steer on server", .steer),
            ],
            optimistic: [
                optimistic("q-pending", "Queued settling", .queue),
                optimistic("s-pending", "Steer settling", .steer),
            ]
        )
        #expect(prompts.map(\.id) == ["s-server", "s-pending", "q-server", "q-pending"])
        #expect(prompts.map(\.isSettling) == [false, true, false, true])
    }

    @Test func optimisticWithoutPendingDelivery_isExcluded() {
        let prompts = SessionChatViewModel.pendingPrompts(
            loaded: [],
            optimistic: [
                SessionChatRow(
                    id: "pending-1",
                    role: .user,
                    text: "Just sent",
                    metadataLabel: "",
                    pendingDelivery: nil,
                    isPending: true
                ),
            ]
        )
        #expect(prompts.isEmpty)
    }

    private func inbox(_ id: String, _ text: String, _ delivery: FollowUpDelivery) -> Message {
        Message(
            id: id,
            role: .user,
            text: text,
            model: nil,
            mode: nil,
            sentAtEpochMs: 1,
            followUpDelivery: delivery,
            isWaitingForPickup: true
        )
    }

    private func optimistic(_ id: String, _ text: String, _ delivery: FollowUpDelivery) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: .user,
            text: text,
            metadataLabel: "",
            pendingDelivery: delivery,
            isPending: true
        )
    }
}
