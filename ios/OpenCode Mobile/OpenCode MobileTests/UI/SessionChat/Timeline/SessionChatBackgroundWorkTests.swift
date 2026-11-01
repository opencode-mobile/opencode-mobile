import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatBackgroundWorkTests {
    private let START: Int64 = 1_700_000_000_000

    @Test func subagents_runningPartsCountAsRunning() {
        let rows = subagents([
            task("prt-1", status: .running, child: "ses-a"),
            task("prt-2", status: .pending, child: "ses-b"),
            task("prt-3", status: .completed, child: "ses-c"),
        ])

        #expect(rows.map(\.status) == [.running, .running, .done])
        #expect(SessionChatBackgroundWork.pill(for: rows)?.label == "2 subagents")
    }

    @Test func subagents_busyChildKeepsFinishedPartRunning() {
        // v1 background tasks complete their tool call while the child session still works.
        let rows = subagents([task("prt-1", status: .completed, child: "ses-a")], busy: ["ses-a"])

        #expect(rows.map(\.status) == [.running])
    }

    @Test func subagents_requestFromChildOrItsDescendantIsWaiting() {
        let rows = subagents(
            [
                task("prt-1", status: .running, child: "ses-a", agent: "general"),
                task("prt-2", status: .running, child: "ses-b", agent: "explore"),
                task("prt-3", status: .running, child: "ses-c", agent: "review"),
            ],
            sessions: [session("ses-grandchild", parent: "ses-b")],
            requests: ["ses-grandchild", "ses-root"]
        )

        #expect(rows.map(\.id) == ["prt-2", "prt-1", "prt-3"])
        #expect(rows.map(\.status) == [.waitingForInput, .running, .running])
    }

    @Test func subagents_waitingSortsFirstThenRunningThenDoneInSpawnOrder() {
        let rows = subagents(
            [
                task("prt-1", status: .completed, child: "ses-a"),
                task("prt-2", status: .running, child: "ses-b"),
                task("prt-3", status: .running, child: "ses-c"),
                task("prt-4", status: .running, child: "ses-d"),
            ],
            requests: ["ses-d"]
        )

        #expect(rows.map(\.id) == ["prt-4", "prt-2", "prt-3", "prt-1"])
    }

    @Test func subagents_ignoreOtherTools() {
        let shell = MessagePart.tool(ToolPart(id: "prt-1", toolName: "bash", status: .running))
        let rows = SessionChatBackgroundWork.subagents(
            messages: [assistant([shell])],
            sessions: [],
            busySessionIDs: [],
            requestSessionIDs: []
        )

        #expect(rows.isEmpty)
    }

    @Test func subagents_agentNameFallsBackToSessionAgentThenTitleSuffix() {
        let rows = subagents(
            [
                task("prt-1", status: .running, child: "ses-a", agent: nil),
                task("prt-2", status: .running, child: "ses-b", agent: nil),
                task("prt-3", status: .running, child: nil, agent: nil),
            ],
            sessions: [
                session("ses-a", parent: "ses-root", agentName: "explore"),
                session("ses-b", parent: "ses-root", title: "Fix tests (@review subagent)"),
            ]
        )

        #expect(rows.map(\.agentName) == ["explore", "review", "Task"])
    }

    @Test func pill_hiddenWhenNothingWorks() {
        let rows = subagents([task("prt-1", status: .completed, child: "ses-a")])

        #expect(SessionChatBackgroundWork.pill(for: rows) == nil)
        #expect(SessionChatBackgroundWork.pill(for: []) == nil)
    }

    @Test func pill_singleRunningSubagentIsSingular() {
        let pill = SessionChatBackgroundWork.pill(for: subagents([task("prt-1", status: .running, child: "ses-a")]))

        #expect(pill == SessionChatBackgroundWorkPill(
            label: "1 subagent",
            needsInput: false,
            accessibilityLabel: "1 subagent running"
        ))
    }

    @Test func pill_oneWaitingNamesTheAgentAndCountsTheRest() {
        let rows = subagents(
            [
                task("prt-1", status: .running, child: "ses-a", agent: "general"),
                task("prt-2", status: .running, child: "ses-b"),
                task("prt-3", status: .running, child: "ses-c"),
            ],
            requests: ["ses-a"]
        )

        #expect(SessionChatBackgroundWork.pill(for: rows) == SessionChatBackgroundWorkPill(
            label: "general needs input · 2 running",
            needsInput: true,
            accessibilityLabel: "general needs input, 2 subagents running"
        ))
    }

    @Test func pill_waitingAloneDropsRunningCount() {
        let rows = subagents([task("prt-1", status: .running, child: "ses-a", agent: "explore")], requests: ["ses-a"])

        #expect(SessionChatBackgroundWork.pill(for: rows)?.label == "explore needs input")
    }

    @Test func pill_severalWaitingCountsThem() {
        let rows = subagents(
            [
                task("prt-1", status: .running, child: "ses-a"),
                task("prt-2", status: .running, child: "ses-b"),
                task("prt-3", status: .running, child: "ses-c"),
            ],
            requests: ["ses-a", "ses-b"]
        )

        #expect(SessionChatBackgroundWork.pill(for: rows)?.label == "2 subagents need input · 1 running")
    }

    @Test func elapsed_runningCountsFromStartAndDoneFreezes() {
        let running = row(status: .running, started: 1_700_000_000_000, finished: nil)
        let done = row(status: .done, started: 1_700_000_000_000, finished: 1_700_000_065_000)
        let doneUnknown = row(status: .done, started: 1_700_000_000_000, finished: nil)

        #expect(running.elapsed(nowMs: 1_700_000_005_000) == "0:05")
        #expect(done.elapsed(nowMs: 1_800_000_000_000) == "1:05")
        #expect(doneUnknown.elapsed(nowMs: 1_800_000_000_000) == nil)
    }

    @Test func finishTimes_recordsOnlyRowsSeenFinishing() {
        let before = [row("prt-1", status: .running), row("prt-2", status: .done)]
        let after = [row("prt-1", status: .done), row("prt-2", status: .done)]

        let times = SessionChatBackgroundWork.finishTimes(
            previous: before, current: after, known: [:], nowMs: 42, seenLive: true
        )

        #expect(times == ["prt-1": 42])
    }

    @Test func finishTimes_keepsKnownAndDropsGoneOrRestarted() {
        let current = [row("prt-1", status: .done), row("prt-2", status: .running)]

        let times = SessionChatBackgroundWork.finishTimes(
            previous: current,
            current: current,
            known: ["prt-1": 10, "prt-2": 20, "prt-gone": 30],
            nowMs: 99,
            seenLive: true
        )

        #expect(times == ["prt-1": 10])
    }

    @Test func subagents_doneRowCarriesRecordedFinishTime() {
        let rows = SessionChatBackgroundWork.subagents(
            messages: [assistant([task("prt-1", status: .completed, child: "ses-a")])],
            sessions: [],
            busySessionIDs: [],
            requestSessionIDs: [],
            localFinishedAtMs: ["prt-1": 1_700_000_005_000]
        )

        #expect(rows.first?.finishedAtMs == 1_700_000_005_000)
    }

    // MARK: - Resumed subagents

    @Test func subagents_resumedChildKeepsOneRowFromTheLatestPart() {
        // v1 resume (`task_id`) writes the same child session id on a new tool part.
        let rows = subagents(
            [
                task("prt-1", status: .completed, child: "ses-a", start: START, end: START + 30_000),
                task("prt-2", status: .running, child: "ses-a", start: START + 60_000),
            ],
            busy: ["ses-a"]
        )

        #expect(rows.map(\.id) == ["prt-2"])
        #expect(rows.first?.status == .running)
        #expect(rows.first?.startedAtMs == START + 60_000)
        #expect(SessionChatBackgroundWork.pill(for: rows)?.label == "1 subagent")
    }

    @Test func subagents_resumedChildWaitingCountsOnce() {
        let rows = subagents(
            [
                task("prt-1", status: .completed, child: "ses-a", agent: "general"),
                task("prt-2", status: .running, child: "ses-a", agent: "general"),
            ],
            busy: ["ses-a"],
            requests: ["ses-a"]
        )

        #expect(SessionChatBackgroundWork.pill(for: rows)?.label == "general needs input")
    }

    @Test func subagents_partsWithoutChildStaySeparate() {
        let rows = subagents([
            task("prt-1", status: .running, child: nil),
            task("prt-2", status: .running, child: nil),
        ])

        #expect(rows.count == 2)
    }

    // MARK: - Finish times

    @Test func finishTime_usesTheToolEndFromTheServer() {
        let rows = subagents(
            [task("prt-1", status: .completed, child: "ses-a", start: START, end: START + 45_000)],
            sessions: [session("ses-a", parent: "ses-root", updatedAt: START + 600_000)]
        )

        #expect(rows.first?.finishedAtMs == START + 45_000)
        #expect(rows.first?.elapsed(nowMs: START + 600_000) == "0:45")
    }

    @Test func finishTime_backgroundCallUsesTheChildSessionUpdate() {
        // A background call settles at spawn; its end is not when the child finished.
        let rows = subagents(
            [task("prt-1", status: .completed, child: "ses-a", start: START, end: START + 1_000, background: true)],
            sessions: [session("ses-a", parent: "ses-root", updatedAt: START + 90_000)]
        )

        #expect(rows.first?.finishedAtMs == START + 90_000)
    }

    @Test func finishTime_serverTimeWinsOverALocalStampAndIgnoresTimesBeforeStart() {
        let tool = ToolPart(
            id: "prt-1", toolName: "task", status: .completed,
            timeStartEpochMs: START, timeEndEpochMs: START + 20_000
        )
        let stale = ToolPart(
            id: "prt-2", toolName: "task", status: .completed,
            timeStartEpochMs: START, timeEndEpochMs: START - 5_000
        )

        #expect(SessionChatBackgroundWork.finishTime(tool, session: nil, local: START + 99_000) == START + 20_000)
        #expect(SessionChatBackgroundWork.finishTime(stale, session: nil, local: START + 7_000) == START + 7_000)
    }

    @Test func finishTimes_findingAFinishAfterAGapStampsNothing() {
        // Running at 12:00:00, app away, done found by the reconnect refresh ten minutes later.
        let before = [row("prt-1", status: .running)]
        let after = [row("prt-1", status: .done)]

        let times = SessionChatBackgroundWork.finishTimes(
            previous: before, current: after, known: [:], nowMs: START + 600_000, seenLive: false
        )

        #expect(times.isEmpty)
        #expect(after[0].elapsed(nowMs: START + 600_000) == nil)
    }

    @Test func finishTimes_skipsRowsWithAServerFinish() {
        let before = [row("prt-1", status: .running)]
        let after = [row("prt-1", status: .done, finished: START + 45_000)]

        let times = SessionChatBackgroundWork.finishTimes(
            previous: before, current: after, known: [:], nowMs: START + 50_000, seenLive: true
        )

        #expect(times.isEmpty)
    }

    // MARK: - Failed and stopped

    @Test func subagents_errorIsFailedAndInterruptedIsStopped_neitherCounts() {
        let rows = subagents(
            [
                task("prt-1", status: .error, child: "ses-a"),
                task("prt-2", status: .error, child: "ses-b", interrupted: true),
            ],
            busy: ["ses-a", "ses-b"]
        )

        #expect(rows.map(\.status) == [.failed, .stopped])
        #expect(rows.allSatisfy { !$0.isActive })
        #expect(SessionChatBackgroundWork.pill(for: rows) == nil)
    }

    // MARK: - Needs-input target

    @Test func needsInputTarget_pagesToTheWaitingSubagentsPermission() {
        let rows = [
            waitingRow("prt-a", child: "ses-a"),
            waitingRow("prt-b", child: "ses-b"),
        ]
        let target = SessionChatBackgroundWork.needsInputTarget(
            subagents: rows,
            sessions: [],
            permissions: [permission("per-root", session: "ses-root"), permission("per-a", session: "ses-a")],
            questions: [question("que-b", session: "ses-b")],
            shownPermissionID: "per-root"
        )

        #expect(target == .permission(requestID: "per-a"))
    }

    @Test func needsInputTarget_findsARequestFromTheSubagentsOwnSubagent() {
        let target = SessionChatBackgroundWork.needsInputTarget(
            subagents: [waitingRow("prt-a", child: "ses-a")],
            sessions: [session("ses-grandchild", parent: "ses-a")],
            permissions: [],
            questions: [question("que-root", session: "ses-root"), question("que-a", session: "ses-grandchild")],
            shownPermissionID: nil
        )

        #expect(target == .question(requestID: "que-a"))
    }

    @Test func needsInputTarget_questionBehindAPermissionOpensTheShownPermission() {
        let target = SessionChatBackgroundWork.needsInputTarget(
            subagents: [waitingRow("prt-a", child: "ses-a")],
            sessions: [],
            permissions: [permission("per-root", session: "ses-root"), permission("per-other", session: "ses-root")],
            questions: [question("que-a", session: "ses-a")],
            shownPermissionID: "per-other"
        )

        #expect(target == .permission(requestID: "per-other"))
    }

    @Test func needsInputTarget_nilWithoutAWaitingSubagentOrItsRequest() {
        let running = SessionChatSubagent(
            id: "prt-a", childSessionID: "ses-a", agentName: "general", title: nil,
            status: .running, startedAtMs: nil, finishedAtMs: nil
        )
        #expect(SessionChatBackgroundWork.needsInputTarget(
            subagents: [running], sessions: [], permissions: [permission("per-a", session: "ses-a")],
            questions: [], shownPermissionID: nil
        ) == nil)
        #expect(SessionChatBackgroundWork.needsInputTarget(
            subagents: [waitingRow("prt-a", child: "ses-a")], sessions: [],
            permissions: [permission("per-root", session: "ses-root")], questions: [], shownPermissionID: nil
        ) == nil)
    }

    // MARK: - Helpers

    private func waitingRow(_ id: String, child: String) -> SessionChatSubagent {
        SessionChatSubagent(
            id: id, childSessionID: child, agentName: "general", title: nil,
            status: .waitingForInput, startedAtMs: nil, finishedAtMs: nil
        )
    }

    private func permission(_ id: String, session: String) -> PermissionRequest {
        PermissionRequest(
            id: id, sessionID: session, permission: "bash", patterns: ["npm test"], metadata: [:],
            always: [], toolMessageID: nil, toolCallID: nil
        )
    }

    private func question(_ id: String, session: String) -> QuestionRequest {
        QuestionRequest(id: id, sessionID: session, questions: [], toolMessageID: nil, toolCallID: nil)
    }

    private func subagents(
        _ parts: [MessagePart],
        sessions: [Session] = [],
        busy: Set<String> = [],
        requests: [String] = []
    ) -> [SessionChatSubagent] {
        SessionChatBackgroundWork.subagents(
            messages: [assistant(parts)],
            sessions: sessions,
            busySessionIDs: busy,
            requestSessionIDs: requests
        )
    }

    private func assistant(_ parts: [MessagePart]) -> Message {
        Message(id: "msg-1", role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: nil)
    }

    private func task(
        _ id: String,
        status: ToolStatus,
        child: String?,
        agent: String? = "general",
        start: Int64 = 1_700_000_000_000,
        end: Int64? = nil,
        background: Bool = false,
        interrupted: Bool = false
    ) -> MessagePart {
        var input: [String: AnyCodable] = ["description": .string("Look around")]
        if let agent { input["subagent_type"] = .string(agent) }
        var metadata: [String: AnyCodable] = [:]
        if let child { metadata["sessionId"] = .string(child) }
        if background { metadata["background"] = .bool(true) }
        return .tool(
            ToolPart(
                id: id,
                toolName: "task",
                status: status,
                input: input,
                metadata: metadata.isEmpty ? nil : metadata,
                timeStartEpochMs: start,
                timeEndEpochMs: end,
                isInterrupted: interrupted
            )
        )
    }

    private func session(
        _ id: String,
        parent: String?,
        title: String = "Subagent",
        agentName: String? = nil,
        updatedAt: Int64? = nil
    ) -> Session {
        var session = Session(
            id: id,
            title: title,
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: parent,
            updatedAtEpochMs: updatedAt
        )
        session.agentName = agentName
        return session
    }

    private func row(
        _ id: String = "prt-1",
        status: SessionChatSubagentStatus,
        started: Int64? = 1_700_000_000_000,
        finished: Int64? = nil
    ) -> SessionChatSubagent {
        SessionChatSubagent(
            id: id,
            childSessionID: "ses-\(id)",
            agentName: "general",
            title: nil,
            status: status,
            startedAtMs: started,
            finishedAtMs: finished
        )
    }
}
