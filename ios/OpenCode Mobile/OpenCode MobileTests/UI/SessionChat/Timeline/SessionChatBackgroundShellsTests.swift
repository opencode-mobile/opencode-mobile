import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatBackgroundShellsTests {
    private func subagent(_ id: String, _ status: SessionChatSubagentStatus, agent: String = "general") -> SessionChatSubagent {
        SessionChatSubagent(
            id: id,
            childSessionID: "ses-\(id)",
            agentName: agent,
            title: nil,
            status: status,
            startedAtMs: 1,
            finishedAtMs: nil
        )
    }

    private func shell(_ id: String, startedAtMs: Int64? = 1, status: AgentShellStatus = .running) -> AgentShell {
        AgentShell(id: id, sessionID: "ses-1", command: "echo \(id)", status: status, startedAtMs: startedAtMs)
    }

    // MARK: Pill

    @Test func shellsAloneShowThePill() {
        let one = SessionChatBackgroundWork.pill(for: [], shells: [shell("a")])
        let three = SessionChatBackgroundWork.pill(for: [], shells: [shell("a"), shell("b"), shell("c")])

        #expect(one == SessionChatBackgroundWorkPill(label: "1 shell", needsInput: false, accessibilityLabel: "1 shell running"))
        #expect(three?.label == "3 shells")
        #expect(three?.accessibilityLabel == "3 shells running")
    }

    @Test func subagentsAndShellsShareThePill() {
        let pill = SessionChatBackgroundWork.pill(for: [subagent("1", .running)], shells: [shell("a"), shell("b")])

        #expect(pill == SessionChatBackgroundWorkPill(
            label: "1 subagent · 2 shells",
            needsInput: false,
            accessibilityLabel: "1 subagent and 2 shells running"
        ))
    }

    @Test func subagentsWithoutShellsKeepTheirLabel() {
        let pill = SessionChatBackgroundWork.pill(for: [subagent("1", .running), subagent("2", .running)], shells: [])

        #expect(pill == SessionChatBackgroundWorkPill(label: "2 subagents", needsInput: false, accessibilityLabel: "2 subagents running"))
    }

    @Test func needsInputCountsShellsAsRunning() {
        let pill = SessionChatBackgroundWork.pill(
            for: [subagent("1", .waitingForInput, agent: "general")],
            shells: [shell("a"), shell("b")]
        )

        #expect(pill == SessionChatBackgroundWorkPill(
            label: "general needs input · 2 running",
            needsInput: true,
            accessibilityLabel: "general needs input, 2 shells running"
        ))
    }

    @Test func endedShellsDoNotCount() {
        #expect(SessionChatBackgroundWork.pill(for: [], shells: [shell("a", status: .exited(code: 0))]) == nil)
        #expect(SessionChatBackgroundWork.pill(for: [subagent("1", .done)], shells: []) == nil)
    }

    // MARK: Shells section

    @Test func shellsSectionListsRunningShellsInStartOrder() {
        let rows = SessionChatBackgroundWork.runningShells([
            shell("late", startedAtMs: 30),
            shell("done", startedAtMs: 5, status: .stopped),
            shell("early", startedAtMs: 10),
            shell("unknown", startedAtMs: nil),
        ])

        #expect(rows.map(\.id) == ["early", "late", "unknown"])
        #expect(SessionChatBackgroundWork.runningShells([shell("x", status: .failed)]).isEmpty)
    }

    // MARK: Copy

    @Test func statusLineNamesHowTheShellEndedAndForHowLong() {
        let start: Int64 = 1_700_000_000_000
        func line(_ status: AgentShellStatus, finishedAfterMs: Int64? = 72_000) -> String {
            let shell = AgentShell(
                id: "sh",
                sessionID: "ses-1",
                command: "npm test",
                status: status,
                startedAtMs: start,
                finishedAtMs: status.isRunning ? nil : finishedAfterMs.map { start + $0 }
            )
            return SessionChatShellPresentation.statusLine(shell, nowMs: start + 72_000)
        }

        #expect(line(.running) == "Running · 1 min 12 s")
        #expect(line(.exited(code: 0)) == "Exited · 1 min 12 s")
        #expect(line(.exited(code: nil), finishedAfterMs: 4_000) == "Exited · 4 s")
        #expect(line(.exited(code: 1)) == "Exited with code 1 · 1 min 12 s")
        #expect(line(.timedOut, finishedAfterMs: 120_000) == "Timed out · 2 min")
        #expect(line(.stopped) == "Stopped · 1 min 12 s")
        #expect(line(.failed) == "Failed · 1 min 12 s")
    }

    @Test func statusLineWithoutStartIsJustTheStatus() {
        let shell = AgentShell(id: "sh", sessionID: "ses-1", command: "ls", status: .running)

        #expect(SessionChatShellPresentation.statusLine(shell, nowMs: 5) == "Running")
    }

    @Test func endedShellWithoutAnEndTimeShowsItsStateAlone() {
        let shell = AgentShell(id: "sh", sessionID: "ses-1", command: "ls", status: .stopped, startedAtMs: 1_700_000_000_000)

        #expect(SessionChatShellPresentation.statusLine(shell, nowMs: 1_700_000_072_000) == "Stopped")
        #expect(SessionChatShellPresentation.elapsed(shell, nowMs: 1_700_000_072_000) == nil)
    }

    @Test func durationFormats() {
        #expect(SessionChatShellPresentation.duration(ms: 0) == "0 s")
        #expect(SessionChatShellPresentation.duration(ms: 59_999) == "59 s")
        #expect(SessionChatShellPresentation.duration(ms: 3_600_000) == "1 h")
        #expect(SessionChatShellPresentation.duration(ms: 3_900_000) == "1 h 5 min")
    }

    @Test func rowTimerFreezesOnceEnded() {
        let running = AgentShell(id: "sh", sessionID: "s", command: "ls", status: .running, startedAtMs: 1_700_000_000_000)
        let ended = running.ended(.exited(code: 0), atMs: 1_700_000_010_000)

        #expect(SessionChatShellPresentation.elapsed(running, nowMs: 1_700_000_064_000) == "1:04")
        #expect(SessionChatShellPresentation.elapsed(ended, nowMs: 1_700_000_064_000) == "0:10")
    }

    @Test func rowAccessibilityReadsShellCommandDescriptionAndTime() {
        #expect(buildShellRowAccessibilityLabel(command: "npm test", description: "Run the tests", elapsed: "1:04")
            == "Shell, npm test, Run the tests, 1:04")
        #expect(buildShellRowAccessibilityLabel(command: "ls", description: nil, elapsed: nil) == "Shell, ls")
    }
}
