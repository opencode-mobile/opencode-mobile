import Foundation
import Testing
@testable import OpenCode_Mobile

@Suite struct ChipTimelineIntroTests {
    private let now: Int64 = 1_000_000

    private func shouldShow(
        available: Bool = true,
        on: Bool = false,
        done: Bool = false,
        snoozedUntil: Int64? = nil,
        toolCalls: Int = ChipTimelineIntro.minToolCalls
    ) -> Bool {
        ChipTimelineIntro.shouldShow(
            chipTimelineAvailable: available,
            chipTimelineOn: on,
            introDone: done,
            snoozedUntilMs: snoozedUntil,
            nowMs: now,
            turnToolCalls: toolCalls
        )
    }

    @Test func freshInstall_startsOnChipTimeline() {
        #expect(ChipTimelineIntro.startingChipTimeline(existingInstall: false))
    }

    @Test func existingInstall_keepsStandardTimelineForTheIntro() {
        #expect(!ChipTimelineIntro.startingChipTimeline(existingInstall: true))
        #expect(shouldShow(on: ChipTimelineIntro.startingChipTimeline(existingInstall: true)))
    }

    @Test func longTurn_withChipTimelineOff_shows() {
        #expect(shouldShow())
    }

    @Test func shortTurn_doesNotShow() {
        #expect(!shouldShow(toolCalls: ChipTimelineIntro.minToolCalls - 1))
    }

    @Test func flagOff_doesNotShow() {
        #expect(!shouldShow(available: false))
    }

    @Test func chipTimelineAlreadyOn_doesNotShow() {
        #expect(!shouldShow(on: true))
    }

    @Test func introDone_doesNotShow() {
        #expect(!shouldShow(done: true))
    }

    @Test func snoozed_showsOnlyAfterSnoozeEnds() {
        #expect(!shouldShow(snoozedUntil: now + 1))
        #expect(shouldShow(snoozedUntil: now))
    }

    @Test func firstLater_snoozesThreeDays() {
        #expect(ChipTimelineIntro.laterSnoozeUntil(snoozedUntilMs: nil, nowMs: now) == now + ChipTimelineIntro.snoozeMs)
    }

    @Test func secondLater_endsIntro() {
        #expect(ChipTimelineIntro.laterSnoozeUntil(snoozedUntilMs: now - 1, nowMs: now) == nil)
    }

    @Test func lastTurnToolCalls_countsVisibleToolsAfterLastUser() {
        func tool(_ id: String, _ name: String = "read") -> MessagePart {
            .tool(ToolPart(id: id, toolName: name, status: .completed))
        }
        let messages = [
            Message(id: "u1", role: .user, text: "first", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "a1", role: .assistant, text: "", parts: [tool("t1"), tool("t2")], model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "u2", role: .user, text: "second", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "a2", role: .assistant, text: "", parts: [tool("t3"), tool("t4", "todowrite")], model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "a3", role: .assistant, text: "", parts: [tool("t5", "bash")], model: nil, mode: nil, sentAtEpochMs: nil),
        ]

        #expect(ChipTimelineIntro.lastTurnToolCalls(messages) == 2)
    }

    @Test func lastTurnToolCalls_queuedFollowUp_stillCountsFinishedTurn() {
        let tools: [MessagePart] = (1...9).map { .tool(ToolPart(id: "t\($0)", toolName: "read", status: .completed)) }
        let messages = [
            Message(id: "u1", role: .user, text: "fix it", model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "a1", role: .assistant, text: "", parts: tools, model: nil, mode: nil, sentAtEpochMs: nil),
            Message(id: "u2", role: .user, text: "queued follow-up", model: nil, mode: nil, sentAtEpochMs: nil),
        ]

        #expect(ChipTimelineIntro.lastTurnToolCalls(messages) == 9)
    }

    @Test func lastTurnToolCalls_noAssistant_isZero() {
        let messages = [Message(id: "u1", role: .user, text: "hi", model: nil, mode: nil, sentAtEpochMs: nil)]

        #expect(ChipTimelineIntro.lastTurnToolCalls(messages) == 0)
    }
}
