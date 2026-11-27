import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatLiveThoughtTests {
    private func user(_ id: String = "user-1", waiting: Bool = false) -> Message {
        Message(id: id, role: .user, text: "Go", model: nil, mode: nil, sentAtEpochMs: 1, isWaitingForPickup: waiting)
    }

    private func assistant(id: String = "assistant-1", _ parts: [MessagePart]) -> Message {
        Message(id: id, role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: 2)
    }

    private func thought(
        _ id: String,
        _ text: String = "Checking the retry clock.",
        streaming: Bool = true,
        start: Int64? = nil,
        end: Int64? = nil
    ) -> MessagePart {
        .reasoning(ReasoningPart(id: id, text: text, isStreaming: streaming, timeStartEpochMs: start, timeEndEpochMs: end))
    }

    // MARK: Which thought is live

    @Test func streamingThoughtAtTheEndOfTheTurnIsLive() {
        let messages = [user(), assistant([.text(TextPart(id: "t1", text: "Looking.", isStreaming: false)), thought("r1")])]
        #expect(SessionChatLiveThought.streamingPartID(in: messages) == "r1")
    }

    @Test func settledThoughtIsNotLive() {
        let messages = [user(), assistant([thought("r1", streaming: false)])]
        #expect(SessionChatLiveThought.streamingPartID(in: messages) == nil)
    }

    @Test func aToolOrTextAfterTheThoughtEndsIt() {
        let tool = MessagePart.tool(ToolPart(id: "tool-1", toolName: "grep", status: .running))
        #expect(SessionChatLiveThought.streamingPartID(in: [user(), assistant([thought("r1"), tool])]) == nil)
        let text = MessagePart.text(TextPart(id: "t1", text: "", isStreaming: true))
        #expect(SessionChatLiveThought.streamingPartID(in: [user(), assistant([thought("r1"), text])]) == nil)
    }

    @Test func hiddenPartsAfterTheThoughtDoNotEndIt() {
        let todo = MessagePart.tool(ToolPart(id: "tool-1", toolName: "todowrite", status: .running))
        let ignored = MessagePart.text(TextPart(id: "t1", text: "context", isStreaming: false, isSynthetic: true))
        let messages = [user(), assistant([thought("r1"), todo, ignored])]
        #expect(SessionChatLiveThought.streamingPartID(in: messages) == "r1")
    }

    @Test func blankOrRedactedThoughtIsNotLive() {
        #expect(SessionChatLiveThought.streamingPartID(in: [user(), assistant([thought("r1", "")])]) == nil)
        #expect(SessionChatLiveThought.streamingPartID(in: [user(), assistant([thought("r1", "[REDACTED]")])]) == nil)
    }

    @Test func onlyTheCurrentTurnCounts() {
        let stale = [user("user-1"), assistant(id: "assistant-1", [thought("r1")]), user("user-2")]
        #expect(SessionChatLiveThought.streamingPartID(in: stale) == nil)
        let queuedPrompt = [user("user-1"), assistant(id: "assistant-1", [thought("r1")]), user("user-2", waiting: true)]
        #expect(SessionChatLiveThought.streamingPartID(in: queuedPrompt) == "r1")
    }

    // MARK: What the sheet shows

    @Test func sheetFollowsTheThoughtWhileItStreamsAndSettles() {
        let live = [user(), assistant([thought("r1", "First [REDACTED] step", start: 1_000)])]
        #expect(SessionChatLiveThought.sheet(partID: "r1", in: live) == SessionChatThoughtSheetState(
            partID: "r1",
            text: "First  step",
            isStreaming: true,
            durationMs: nil
        ))
        let settled = [user(), assistant([thought("r1", "First step, then the fix.", streaming: false, start: 1_000, end: 2_500)])]
        #expect(SessionChatLiveThought.sheet(partID: "r1", in: settled) == SessionChatThoughtSheetState(
            partID: "r1",
            text: "First step, then the fix.",
            isStreaming: false,
            durationMs: 1_500
        ))
    }

    @Test func sheetKeepsAnEarlierThoughtAfterTheTurnMovesOn() {
        let messages = [
            user(),
            assistant(id: "assistant-1", [thought("r1", "Earlier", streaming: false), thought("r2", "Later")]),
        ]
        #expect(SessionChatLiveThought.sheet(partID: "r1", in: messages)?.text == "Earlier")
    }

    @Test func sheetClosesWhenTheThoughtIsGone() {
        #expect(SessionChatLiveThought.sheet(partID: "r1", in: [user()]) == nil)
        #expect(SessionChatLiveThought.sheet(partID: "r1", in: [user(), assistant([thought("r1", "[REDACTED]")])]) == nil)
    }

    @MainActor
    @Test func sheetSublineSaysThinkingUntilTheThoughtSettles() {
        let live = SessionChatThoughtSheetState(partID: "r1", text: "Checking.", isStreaming: true, durationMs: nil)
        #expect(thoughtPresentation(live)?.durationLabel == "Thinking\u{2026}")
        let settled = SessionChatThoughtSheetState(partID: "r1", text: "Checking.", isStreaming: false, durationMs: 1_500)
        #expect(thoughtPresentation(settled)?.durationLabel == "1.5s")
        #expect(settled.detailTarget.body == "Checking.")
    }

    @MainActor
    private func thoughtPresentation(_ state: SessionChatThoughtSheetState) -> ToolResultPresentation.Thought? {
        guard case let .thought(thought) = state.detailTarget.presentation else { return nil }
        return thought
    }

    // MARK: Turn pill

    private func slot(
        label: SessionTurnLabel? = .working,
        fading: Bool = false,
        awaiting: Bool = false,
        items: [SessionChatActivityItem] = [],
        keyboard: Bool = false,
        thinking: Bool = true
    ) -> SessionChatTurnStatusSlot? {
        SessionChatPillSlots.turnStatus(
            label: label,
            fading: fading,
            isAwaitingUser: awaiting,
            items: items,
            keyboardVisible: keyboard,
            awayFromEnd: false,
            thinking: thinking
        )
    }

    @Test func workingPillReadsThinkingAndOpensTheThought() {
        let pill = slot()
        #expect(pill?.phase == .working(label: "Thinking"))
        #expect(pill?.tapItem == .thinking)
        #expect(pill?.accessibilityLabel == "Thinking. Show thought")
        #expect(slot(thinking: false) == SessionChatTurnStatusSlot(phase: .working(label: "Working"), compact: false, leaving: false))
    }

    @Test func thinkingPillStillFoldsWithTheKeyboard() {
        #expect(slot(keyboard: true)?.compact == true)
    }

    @Test func otherTurnLabelsAndRequestsWinOverThinking() {
        #expect(slot(label: .text("Compacting\u{2026}"))?.phase == .working(label: "Compacting\u{2026}"))
        #expect(slot(label: .text("Compacting\u{2026}"))?.tapItem == nil)
        #expect(slot(label: WorkingTurnIndicator.idleLabel)?.phase == .done)
        #expect(slot(label: nil) == nil)
        #expect(slot(awaiting: true)?.phase == .attention(label: "Waiting for you", mark: .pulsingDot))
        #expect(slot(items: [.reviewPermission])?.tapItem == .reviewPermission)
    }

    @Test func fadingPillKeepsItsLeavingWorkingLabel() {
        let pill = slot(fading: true)
        #expect(pill?.phase == .working(label: "Working"))
        #expect(pill?.leaving == true)
        #expect(pill?.tapItem == nil)
    }

    @Test func thinkingIsNeverASecondaryPill() {
        #expect(SessionChatPillSlots.secondaryItems([.thinking, .queued(count: 1)]) == [.queued(count: 1)])
    }
}
