import Foundation
import Testing
@testable import OpenCode_Mobile

/// Running / Done tabs and the row data of the background work panel (#966).
struct SessionChatBackgroundWorkTabsTests {
    private let START: Int64 = 1_700_000_000_000

    @Test func tabs_runningHoldsActiveRowsAndDoneTheRestNewestFirst() {
        let rows = [
            row("prt-1", .waitingForInput),
            row("prt-2", .running),
            row("prt-3", .done, finished: START + 10_000),
            row("prt-4", .failed, finished: START + 30_000),
            row("prt-5", .stopped, finished: START + 20_000),
            row("prt-6", .done),
            row("prt-7", .done),
        ]

        let tabs = SessionChatBackgroundWork.tabs(rows)

        #expect(tabs.running.map(\.id) == ["prt-1", "prt-2"])
        #expect(tabs.done.map(\.id) == ["prt-4", "prt-5", "prt-3", "prt-7", "prt-6"])
    }

    @Test func doneDot_firstRowsCountAsSeenAndALaterFinishDots() {
        let first = [row("prt-1", .done, finished: START + 1), row("prt-2", .running)]
        let seen = SessionChatBackgroundWork.seenDoneIDs(previous: nil, subagents: first, viewingDone: false)
        #expect(!SessionChatBackgroundWork.hasUnseenDone(first, seenDoneIDs: seen))

        let later = [row("prt-1", .done, finished: START + 1), row("prt-2", .done, finished: START + 2)]
        let stillSeen = SessionChatBackgroundWork.seenDoneIDs(previous: seen, subagents: later, viewingDone: false)
        #expect(SessionChatBackgroundWork.hasUnseenDone(later, seenDoneIDs: stillSeen))

        let viewed = SessionChatBackgroundWork.seenDoneIDs(previous: stillSeen, subagents: later, viewingDone: true)
        #expect(!SessionChatBackgroundWork.hasUnseenDone(later, seenDoneIDs: viewed))
    }

    @Test func doneDot_waitsForTheFirstRowsAndDotsARestartedSubagentAgain() {
        #expect(SessionChatBackgroundWork.seenDoneIDs(previous: nil, subagents: [], viewingDone: false) == nil)
        #expect(!SessionChatBackgroundWork.hasUnseenDone([row("prt-1", .done)], seenDoneIDs: nil))

        let seen = SessionChatBackgroundWork.seenDoneIDs(previous: nil, subagents: [row("prt-1", .done)], viewingDone: false)
        let restarted = SessionChatBackgroundWork.seenDoneIDs(previous: seen, subagents: [row("prt-1", .running)], viewingDone: false)
        let finishedAgain = [row("prt-1", .done)]
        let afterFinish = SessionChatBackgroundWork.seenDoneIDs(previous: restarted, subagents: finishedAgain, viewingDone: false)

        #expect(SessionChatBackgroundWork.hasUnseenDone(finishedAgain, seenDoneIDs: afterFinish))
    }

    @Test func rowText_runningShowsStepCountAndLiveTimer() {
        let subagent = row("prt-1", .running, title: "Find float money math", currentStep: "Searching toFixed", toolCount: 12)

        let text = SessionChatBackgroundWork.rowText(subagent, nowMs: START + 64_000)

        #expect(text == SessionChatSubagentRowText(
            title: "Find float money math", tag: nil, detail: "Searching toFixed", detailCaption: "12 tools", time: "1:04"
        ))
        #expect(SessionChatBackgroundWork.toolCountLabel(1) == "1 tool")
    }

    @Test func rowText_waitingSaysSoAndLeavesOutMissingBits() {
        let text = SessionChatBackgroundWork.rowText(row("prt-1", .waitingForInput), nowMs: START + 5_000)

        #expect(text == SessionChatSubagentRowText(
            title: "general", tag: nil, detail: "Waiting for input", detailCaption: nil, time: "0:05"
        ))
    }

    @Test func rowText_doneShowsResultDurationAndWhenItFinished() {
        let subagent = row(
            "prt-1", .done, finished: START + 151_000, title: "Map invoice flow", agentTag: "explore", result: "parse → render"
        )

        let text = SessionChatBackgroundWork.rowText(subagent, nowMs: START + 151_000 + 4 * 60_000)

        #expect(text == SessionChatSubagentRowText(
            title: "Map invoice flow", tag: "explore", detail: "parse → render", detailCaption: "4m ago", time: "2:31"
        ))
    }

    @Test func rowText_unknownDurationIsHiddenNotZero() {
        let unknownEnd = row("prt-1", .done)
        let endAtStart = row("prt-2", .done, finished: START)
        let unknownStart = row("prt-3", .done, started: nil, finished: START + 9_000)
        let failedAtOnce = row("prt-4", .failed, finished: START + 300)

        #expect(unknownEnd.elapsed(nowMs: START + 60_000) == nil)
        #expect(SessionChatBackgroundWork.rowText(unknownEnd, nowMs: START + 60_000).detailCaption == nil)
        #expect(endAtStart.elapsed(nowMs: START + 60_000) == nil)
        #expect(unknownStart.elapsed(nowMs: START + 60_000) == nil)
        #expect(failedAtOnce.elapsed(nowMs: START + 60_000) == nil)
    }

    @Test func finishTime_ignoresASettleStampedWithTheStart() {
        // A live settle with no running event before it carries the event time as both ends.
        let rows = subagents([task("prt-1", status: .completed, end: START)])

        #expect(rows.first?.finishedAtMs == nil)
        #expect(rows.first?.elapsed(nowMs: START + 60_000) == nil)
    }

    @Test func agentTag_onlyForANamedAgentOtherThanGeneral() {
        let rows = subagents([
            task("prt-1", status: .running, agent: "general"),
            task("prt-2", status: .running, agent: "explore"),
            task("prt-3", status: .running, agent: nil),
        ])

        #expect(rows.map(\.agentTag) == [nil, "explore", nil])
        #expect(SessionChatBackgroundWork.agentTag("General") == nil)
        #expect(SessionChatBackgroundWork.agentTag(" ") == nil)
    }

    @Test func v1_classicSummaryGivesStepAndCount() {
        let summary: AnyCodable = .array([
            .dictionary([
                "id": .string("prt-c1"),
                "tool": .string("read"),
                "state": .dictionary(["status": .string("completed"), "input": .dictionary(["filePath": .string("src/format.ts")])]),
            ]),
            .dictionary([
                "id": .string("prt-c2"),
                "tool": .string("grep"),
                "state": .dictionary(["status": .string("running"), "input": .dictionary(["pattern": .string("toFixed")])]),
            ]),
        ])
        let rows = subagents([task("prt-1", status: .running, metadata: ["sessionId": .string("ses-a"), "summary": summary])])

        #expect(rows.first?.toolCount == 2)
        #expect(rows.first?.currentStep?.contains("toFixed") == true)
    }

    @Test func v1_currentResultTagsGiveTheFirstLineAndNoSummaryMeansNoStep() {
        // v1 1.17+: no child summary; the output wraps the reply in <task> and <task_result>.
        let rows = subagents([
            task(
                "prt-1",
                status: .completed,
                end: START + 30_000,
                output: "<task id=\"ses-a\" state=\"completed\">\n<task_result>\n## Found 2 spots\n- src/format.ts:9\n</task_result>\n</task>"
            ),
        ])

        #expect(rows.first?.result == "Found 2 spots")
        #expect(rows.first?.currentStep == nil)
        #expect(rows.first?.toolCount == nil)
        #expect(rows.first?.elapsed(nowMs: START + 99_000) == "0:30")
    }

    @Test func v1_failureReasonDropsTheTaskIdLead() {
        let rows = subagents([
            task("prt-1", status: .error, end: START + 5_000, error: "Subagent failed (task_id: ses-a): Rate limited\nretry later"),
        ])

        #expect(rows.first?.result == "Rate limited")
    }

    @Test func v2_subagentBodyGivesTheResultAndCancelReads() {
        let rows = subagents([
            task(
                "prt-1",
                status: .completed,
                name: "subagent",
                end: START + 2_000,
                metadata: ["sessionID": .string("ses-a"), "status": .string("completed")],
                output: "<subagent sessionID=\"ses-a\" state=\"completed\">\nFound 2 spots:\n- `src/format.ts:9`\n</subagent>"
            ),
            task(
                "prt-2",
                status: .error,
                name: "subagent",
                end: START + 2_000,
                metadata: ["sessionID": .string("ses-b")],
                error: "Subagent cancelled (sessionID: ses-b)"
            ),
        ])

        #expect(rows.map(\.result) == ["Found 2 spots:", "Subagent cancelled"])
        #expect(rows.map(\.childSessionID) == ["ses-a", "ses-b"])
    }

    @Test func v2_backgroundStartNoteIsNotAResult() {
        let rows = subagents([
            task(
                "prt-1",
                status: .completed,
                name: "subagent",
                end: START + 100,
                metadata: ["sessionID": .string("ses-a"), "status": .string("running"), "background": .bool(true)],
                output: "The subagent is working in the background (sessionID: ses-a)."
            ),
        ])

        #expect(rows.first?.result == nil)
    }

    @Test func stoppedWithoutAReasonSaysStopped() {
        let rows = subagents([task("prt-1", status: .error, end: START + 1_000, interrupted: true)])

        #expect(rows.first?.status == .stopped)
        #expect(rows.first?.result == "Stopped")
    }

    private func subagents(_ parts: [MessagePart]) -> [SessionChatSubagent] {
        SessionChatBackgroundWork.subagents(
            messages: [Message(id: "msg-1", role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: nil)],
            sessions: [],
            busySessionIDs: [],
            requestSessionIDs: []
        )
    }

    private func task(
        _ id: String,
        status: ToolStatus,
        name: String = "task",
        agent: String? = "general",
        end: Int64? = nil,
        metadata: [String: AnyCodable]? = nil,
        output: String? = nil,
        error: String? = nil,
        interrupted: Bool = false
    ) -> MessagePart {
        var input: [String: AnyCodable] = ["description": .string("Look around")]
        if let agent { input[name == "subagent" ? "agent" : "subagent_type"] = .string(agent) }
        return .tool(
            ToolPart(
                id: id,
                toolName: name,
                status: status,
                error: error,
                input: input,
                output: output,
                metadata: metadata ?? ["sessionId": .string("ses-\(id)")],
                timeStartEpochMs: START,
                timeEndEpochMs: end,
                isInterrupted: interrupted
            )
        )
    }

    private func row(
        _ id: String,
        _ status: SessionChatSubagentStatus,
        started: Int64? = 1_700_000_000_000,
        finished: Int64? = nil,
        title: String? = nil,
        agentTag: String? = nil,
        currentStep: String? = nil,
        toolCount: Int? = nil,
        result: String? = nil
    ) -> SessionChatSubagent {
        SessionChatSubagent(
            id: id,
            childSessionID: "ses-\(id)",
            agentName: "general",
            title: title,
            status: status,
            startedAtMs: started,
            finishedAtMs: finished,
            agentTag: agentTag,
            currentStep: currentStep,
            toolCount: toolCount,
            result: result
        )
    }
}
