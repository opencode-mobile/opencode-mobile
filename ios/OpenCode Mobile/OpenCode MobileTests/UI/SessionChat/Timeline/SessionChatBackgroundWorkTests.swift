import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatBackgroundWorkTests {
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

        let times = SessionChatBackgroundWork.finishTimes(previous: before, current: after, known: [:], nowMs: 42)

        #expect(times == ["prt-1": 42])
    }

    @Test func finishTimes_keepsKnownAndDropsGoneOrRestarted() {
        let current = [row("prt-1", status: .done), row("prt-2", status: .running)]

        let times = SessionChatBackgroundWork.finishTimes(
            previous: current,
            current: current,
            known: ["prt-1": 10, "prt-2": 20, "prt-gone": 30],
            nowMs: 99
        )

        #expect(times == ["prt-1": 10])
    }

    @Test func subagents_doneRowCarriesRecordedFinishTime() {
        let rows = SessionChatBackgroundWork.subagents(
            messages: [assistant([task("prt-1", status: .completed, child: "ses-a")])],
            sessions: [],
            busySessionIDs: [],
            requestSessionIDs: [],
            finishedAtMs: ["prt-1": 5_000]
        )

        #expect(rows.first?.finishedAtMs == 5_000)
    }

    // MARK: - Helpers

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
        agent: String? = "general"
    ) -> MessagePart {
        var input: [String: AnyCodable] = ["description": .string("Look around")]
        if let agent { input["subagent_type"] = .string(agent) }
        return .tool(
            ToolPart(
                id: id,
                toolName: "task",
                status: status,
                input: input,
                metadata: child.map { ["sessionId": .string($0)] },
                timeStartEpochMs: 1_700_000_000_000
            )
        )
    }

    private func session(
        _ id: String,
        parent: String?,
        title: String = "Subagent",
        agentName: String? = nil
    ) -> Session {
        var session = Session(
            id: id,
            title: title,
            slug: nil,
            projectID: nil,
            directory: nil,
            parentID: parent,
            updatedAtEpochMs: nil
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
