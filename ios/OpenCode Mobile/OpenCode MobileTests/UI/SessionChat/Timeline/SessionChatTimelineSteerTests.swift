import Foundation
import Testing
@testable import OpenCode_Mobile

/// Steering in the timeline (#902): pending steers show as bubbles, consecutive steers
/// share one label, and delivery replaces the pending bubble in place.
struct SessionChatTimelineSteerTests {
    @Test func waitingSteerDuringRun_isPendingSteerWithLabel() {
        let frame = project([prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", .steer)])

        #expect(frame.rows.map(\.id) == ["u1", "a1", "u2"])
        #expect(frame.rows[0].steer == nil)
        #expect(frame.rows[2].steer == SessionChatSteer(isPending: true, showsLabel: true))
    }

    @Test func waitingQueuedPrompt_staysOutOfTimeline() {
        let frame = project([prompt("u1", "Start"), running("a1"), waiting("u2", "Later", .queue)])

        #expect(frame.rows.map(\.id) == ["u1", "a1"])
    }

    @Test func steerAndQueueTogether_onlySteerShows() {
        let frame = project([
            prompt("u1", "Start"),
            running("a1"),
            waiting("u2", "Steer this", .steer),
            waiting("u3", "Queue this", .queue),
        ])

        #expect(frame.rows.map(\.id) == ["u1", "a1", "u2"])
        #expect(frame.rows.last?.steer?.isPending == true)
    }

    @Test func consecutiveSteers_shareOneLabelOnTheFirst() {
        let frame = project([
            prompt("u1", "Start"),
            running("a1"),
            waiting("u2", "First", .steer),
            waiting("u3", "Second", .steer),
        ])

        #expect(frame.rows[2].steer == SessionChatSteer(isPending: true, showsLabel: true))
        #expect(frame.rows[3].steer == SessionChatSteer(isPending: true, showsLabel: false))
    }

    @Test func noticeBetweenSteers_doesNotSplitTheGroup() {
        func row(_ id: String, _ role: MessageRole, _ delivery: FollowUpDelivery? = nil) -> TimelineRow {
            TimelineRow(
                id: id,
                role: role,
                text: id,
                metadataLabel: "",
                pendingDelivery: delivery,
                contentRevision: ContentRevision.of(id),
                layoutRevision: 1
            )
        }
        let marked = SessionChatTimelineProjector.markSteers(
            [row("u2", .user, .steer), row("n1", .notice), row("u3", .user, .steer)],
            previousByID: [:],
            listIDAliases: [:],
            runIsActive: true
        )

        #expect(marked.map(\.steer) == [
            SessionChatSteer(isPending: true, showsLabel: true),
            nil,
            SessionChatSteer(isPending: true, showsLabel: false),
        ])
    }

    @Test func steersSplitByAssistantOutput_eachGetALabel() {
        let first = project([prompt("u1", "Start"), running("a1"), waiting("u2", "First", .steer)])
        let delivered = project(
            [prompt("u1", "Start"), done("a1"), prompt("u2", "First"), running("a2"), waiting("u3", "Second", .steer)],
            previous: first
        )

        #expect(delivered.rows.first { $0.id == "u2" }?.steer == SessionChatSteer(isPending: false, showsLabel: true))
        #expect(delivered.rows.first { $0.id == "u3" }?.steer == SessionChatSteer(isPending: true, showsLabel: true))
    }

    @Test func delivery_replacesPendingBubbleInPlaceAndKeepsLabel() {
        let pending = project([prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", .steer)])
        // The server's user message: same id, no longer waiting (SSE or REST refresh).
        let delivered = project(
            [prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), running("a2")],
            previous: pending
        )

        #expect(delivered.rows.map(\.id) == ["u1", "a1", "u2", "a2"])
        #expect(delivered.rows.filter { $0.id == "u2" }.count == 1)
        #expect(delivered.rows[2].steer == SessionChatSteer(isPending: false, showsLabel: true))
        #expect(!delivered.insertedRowIDs.contains("u2"))
        #expect(!delivered.removedRowIDs.contains("u2"))
        #expect(delivered.changedRowIDs.contains("u2"))
    }

    @Test func deliveredSteer_keepsLabelAfterTurnEnds() {
        let pending = project([prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", .steer)])
        let delivered = project([prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), running("a2")], previous: pending)
        let idle = project([prompt("u1", "Start"), done("a1"), prompt("u2", "Use staging"), done("a2")], previous: delivered)

        #expect(idle.rows[2].steer == SessionChatSteer(isPending: false, showsLabel: true))
    }

    @Test func idleSendFromAnotherClient_isNotLabelled() {
        // v2 enqueues an idle prompt as a steer; the finished turn means no run to steer.
        let waitingFrame = project([prompt("u1", "Start"), done("a1"), waiting("u2", "Next", .steer)])
        let delivered = project([prompt("u1", "Start"), done("a1"), prompt("u2", "Next"), running("a2")], previous: waitingFrame)

        #expect(waitingFrame.rows.last?.steer == nil)
        #expect(delivered.rows[2].steer == nil)
    }

    @Test func optimisticBusySteer_handsOffToServerRowWithoutDuplicate() {
        let id = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
        let optimisticRow = TimelineRow(
            id: id,
            role: .user,
            text: "Use staging",
            metadataLabel: "",
            pendingDelivery: .steer,
            isPending: true,
            blocks: [.text(blockID: PresentationBlockId.direct(id), text: "Use staging", renderedMarkdown: nil)],
            contentRevision: ContentRevision.of("Use staging"),
            layoutRevision: 1
        )
        let optimistic = OptimisticInputs(pendingRows: [optimisticRow], baselineIDs: ["u1", "a1"], mergePending: true)
        let base = [prompt("u1", "Start"), running("a1")]

        let sent = project(base, optimistic: optimistic)
        #expect(sent.rows.map(\.id) == ["u1", "a1", id])
        #expect(sent.rows.last?.steer == SessionChatSteer(isPending: true, showsLabel: true))

        let echoed = project(base + [waiting(id, "Use staging", .steer)], optimistic: optimistic, previous: sent)
        #expect(echoed.rows.map(\.id) == ["u1", "a1", id])
        #expect(echoed.rows.last?.steer == SessionChatSteer(isPending: true, showsLabel: true))

        let delivered = project(
            [prompt("u1", "Start"), done("a1"), prompt(id, "Use staging"), running("a2")],
            optimistic: optimistic,
            previous: echoed
        )
        #expect(delivered.rows.map(\.id) == ["u1", "a1", id, "a2"])
        #expect(delivered.rows[2].steer == SessionChatSteer(isPending: false, showsLabel: true))
    }

    @Test func multiStepRun_optimisticSteerStaysAfterStreamingStepThroughHandoff() {
        // u1, a1 done, a2 streaming: the steer belongs after a2, before and after its echo.
        let id = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
        let optimistic = OptimisticInputs(
            pendingRows: [optimisticRow(id, "Use staging", .steer)],
            baselineIDs: ["u1", "a1", "a2"],
            mergePending: true
        )
        let base = [prompt("u1", "Start"), done("a1"), streaming("a2")]
        let beforeSend = project(base)

        let sent = project(base, optimistic: optimistic, previous: beforeSend)
        #expect(sent.rows.map(\.id) == ["u1", "a1", "a2", id])

        let echoed = project(base + [waiting(id, "Use staging", .steer)], optimistic: optimistic, previous: sent)
        #expect(echoed.rows.map(\.id) == ["u1", "a1", "a2", id])
        #expect(echoed.insertedRowIDs.isEmpty)
        #expect(echoed.removedRowIDs.isEmpty)
        #expect(echoed.rows.last?.steer == SessionChatSteer(isPending: true, showsLabel: true))

        let delivered = project(
            [prompt("u1", "Start"), done("a1"), done("a2"), prompt(id, "Use staging"), streaming("a3")],
            optimistic: optimistic,
            previous: echoed
        )
        #expect(delivered.rows.map(\.id) == ["u1", "a1", "a2", id, "a3"])
        #expect(!delivered.insertedRowIDs.contains(id) && !delivered.removedRowIDs.contains(id))
        #expect(delivered.rows[3].steer == SessionChatSteer(isPending: false, showsLabel: true))
    }

    @Test func multiStepRun_plainSendStillMovesAboveStreamingReply() {
        let id = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
        let optimistic = OptimisticInputs(
            pendingRows: [optimisticRow(id, "Hello", nil)],
            baselineIDs: ["u1", "a1", "a2"],
            mergePending: true
        )

        let sent = project([prompt("u1", "Start"), done("a1"), streaming("a2")], optimistic: optimistic)

        #expect(sent.rows.map(\.id) == ["u1", "a1", id, "a2"])
    }

    @Test func optimisticIdleSend_waitingSteerEchoDoesNotDuplicate() {
        let id = "msg_19a1b2c3d4e5AbCdEfGhIjKlMn"
        let optimisticRow = TimelineRow(
            id: id,
            role: .user,
            text: "Hello",
            metadataLabel: "",
            isPending: true,
            blocks: [.text(blockID: PresentationBlockId.direct(id), text: "Hello", renderedMarkdown: nil)],
            contentRevision: ContentRevision.of("Hello"),
            layoutRevision: 1
        )
        let optimistic = OptimisticInputs(pendingRows: [optimisticRow], baselineIDs: [], mergePending: true)

        let echoed = project([waiting(id, "Hello", .steer)], optimistic: optimistic)

        #expect(echoed.rows.map(\.id) == [id])
        #expect(echoed.rows.first?.isPending == true)
        #expect(echoed.rows.first?.steer == nil)
    }

    @Test func optimisticQueuedSend_staysOutOfTimeline() {
        let row = SessionChatRow(id: "q", role: .user, text: "Later", metadataLabel: "", pendingDelivery: .queue, isPending: true)
        let steer = SessionChatRow(id: "s", role: .user, text: "Now", metadataLabel: "", pendingDelivery: .steer, isPending: true)

        #expect(SessionChatTimelineProjector.displayedOptimisticSendRows([row, steer]).map(\.id) == ["s"])
    }

    @Test @MainActor func optimisticSteerInsert_isPendingSteerBeforeAnyEcho() {
        let running = project([prompt("u1", "Start"), running("a1"), waiting("u2", "First", .steer)])
        let steer = SessionChatRow(id: "s", role: .user, text: "Second", metadataLabel: "", pendingDelivery: .steer, isPending: true)
        let idle = SessionChatRow(id: "i", role: .user, text: "Hello", metadataLabel: "", isPending: true)

        #expect(
            SessionChatTimeline.markingOptimisticSteers([steer], after: running).first?.steer
                == SessionChatSteer(isPending: true, showsLabel: false)
        )
        #expect(SessionChatTimeline.markingOptimisticSteers([idle], after: running).first?.steer == nil)
        #expect(
            SessionChatTimeline.markingOptimisticSteers([steer], after: .empty).first?.steer
                == SessionChatSteer(isPending: true, showsLabel: true)
        )
    }

    @Test func steerMark_survivesConversionToSessionChatRow() {
        let frame = project([prompt("u1", "Start"), running("a1"), waiting("u2", "Use staging", .steer)])
        let row = frame.rows[2].toSessionChatRow()

        #expect(row.steer == SessionChatSteer(isPending: true, showsLabel: true))
        #expect(TimelineRow.fromSessionChatRow(row).steer == row.steer)
    }

    // MARK: - Helpers

    private func project(
        _ messages: [Message],
        optimistic: OptimisticInputs = .none,
        previous: TimelineFrame? = nil
    ) -> TimelineFrame {
        let frame = DisplayFrameBuilder.build(
            sessionKey: "ses-1",
            frameRevision: (previous?.frameRevision ?? 0) + 1,
            sourceRevision: (previous?.frameRevision ?? 0) + 1,
            displayMessages: messages.map { DisplayMessage(message: $0) }
        )
        return SessionChatTimelineProjector.project(
            frame: frame,
            optimistic: optimistic,
            previous: previous,
            metadataLabel: { _ in "" }
        )
    }

    private func prompt(_ id: String, _ text: String) -> Message {
        Message(
            id: id,
            role: .user,
            text: text,
            parts: [.text(TextPart(id: "\(id):text", text: text, isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
    }

    private func waiting(_ id: String, _ text: String, _ delivery: FollowUpDelivery) -> Message {
        prompt(id, text).withPickupState(isWaitingForPickup: true, followUpDelivery: delivery)
    }

    private func optimisticRow(_ id: String, _ text: String, _ delivery: FollowUpDelivery?) -> TimelineRow {
        TimelineRow(
            id: id,
            role: .user,
            text: text,
            metadataLabel: "",
            pendingDelivery: delivery,
            isPending: true,
            blocks: [.text(blockID: PresentationBlockId.direct(id), text: text, renderedMarkdown: nil)],
            contentRevision: ContentRevision.of(text),
            layoutRevision: 1
        )
    }

    private func streaming(_ id: String) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: "Working",
            parts: [.text(TextPart(id: "\(id):text", text: "Working", isStreaming: true))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil
        )
    }

    private func running(_ id: String) -> Message {
        assistant(id, completedAtEpochMs: nil)
    }

    private func done(_ id: String) -> Message {
        assistant(id, completedAtEpochMs: 2)
    }

    private func assistant(_ id: String, completedAtEpochMs: Int64?) -> Message {
        Message(
            id: id,
            role: .assistant,
            text: "Working",
            parts: [.text(TextPart(id: "\(id):text", text: "Working", isStreaming: false))],
            model: nil,
            mode: nil,
            sentAtEpochMs: nil,
            completedAtEpochMs: completedAtEpochMs
        )
    }
}
