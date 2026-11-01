import Foundation
import Testing
@testable import OpenCode_Mobile

/// Payloads captured from opencode v2.2.2 (see `api/opencode-shells.openapi.yaml`).
enum AgentShellFixtures {
    static let listJSON = #"{"location":{"directory":"/tmp/project"},"data":[{"id":"sh_197d5e595001Fhi2rsSX7g6Gia","status":"running","command":"for i in $(seq 1 40); do echo line $i; sleep 0.5; done","cwd":"/tmp/project","shell":"/bin/zsh","file":"/tmp/data/opencode/shell/global/sh_197d5e595001Fhi2rsSX7g6Gia.out","pid":11227,"metadata":{"sessionID":"ses_test"},"time":{"started":1793548739990}}]}"#
    static let outputJSON = #"{"location":{"directory":"/tmp/project"},"data":{"output":"line 1\nline 2\nline 3\nline 4\nline 5\n","cursor":35,"size":35,"truncated":false}}"#
    static let probeJSON = #"{"location":{"directory":"/tmp/project"},"data":{"output":"","cursor":1988895,"size":1988895,"truncated":false}}"#
    static let notFoundJSON = #"{"_tag":"ShellNotFoundError","id":"sh_197d5e595001Fhi2rsSX7g6Gia","message":"Shell command not found: sh_197d5e595001Fhi2rsSX7g6Gia"}"#
    static let createdEvent = #"{"id":"evt_1","created":1793548740025,"type":"shell.created","location":{"directory":"/tmp/project"},"data":{"info":{"id":"sh_197d5e595001Fhi2rsSX7g6Gia","status":"running","command":"for i in $(seq 1 40); do echo line $i; sleep 0.5; done","cwd":"/tmp/project","shell":"/bin/zsh","file":"/tmp/data/opencode/shell/global/sh_197d5e595001Fhi2rsSX7g6Gia.out","metadata":{"sessionID":"ses_test"},"time":{"started":1793548739990}}}}"#
    static let exitedEvent = #"{"id":"evt_2","created":1793548742191,"type":"shell.exited","location":{"directory":"/tmp/project"},"data":{"id":"sh_197d5ee10001pAROBiMUStdqpO","exit":3,"status":"exited"}}"#
    static let killedEvent = #"{"id":"evt_3","created":1793548743748,"type":"shell.exited","location":{"directory":"/tmp/project"},"data":{"id":"sh_197d5e595001Fhi2rsSX7g6Gia","status":"killed"}}"#
    static let deletedEvent = #"{"id":"evt_4","created":1793548743748,"type":"shell.deleted","location":{"directory":"/tmp/project"},"data":{"id":"sh_197d5e595001Fhi2rsSX7g6Gia"}}"#

    static let runningID = "sh_197d5e595001Fhi2rsSX7g6Gia"

    static func event(_ line: String) throws -> OpencodeEvent {
        try #require(EventDomainAdapter().parseEvent(dataLine: line))
    }

    static func shell(_ id: String, session: String = "ses_test", startedAtMs: Int64 = 1, status: AgentShellStatus = .running) -> AgentShell {
        AgentShell(id: id, sessionID: session, command: "echo \(id)", status: status, startedAtMs: startedAtMs, canStop: true)
    }
}

struct V1AgentShellAdapterTests {
    private func bash(
        _ id: String,
        status: ToolStatus,
        output: String? = nil,
        metadata: [String: AnyCodable]? = nil,
        interrupted: Bool = false
    ) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "bash",
            status: status,
            input: ["command": .string("npm test"), "description": .string("Run the tests")],
            output: output,
            metadata: metadata,
            timeStartEpochMs: 1_700_000_000_000,
            timeEndEpochMs: status == .running ? nil : 1_700_000_005_000,
            isInterrupted: interrupted
        ))
    }

    private func transcript(_ parts: [MessagePart]) -> [Message] {
        [Message(id: "msg-1", role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: 1)]
    }

    @Test func runningBashPartIsARunningShellThatCannotStop() {
        let shells = V1AgentShellAdapter.shells(in: transcript([bash("prt-1", status: .running)]), sessionID: "ses-1")

        #expect(shells == [AgentShell(
            id: "prt-1",
            sessionID: "ses-1",
            command: "npm test",
            description: "Run the tests",
            status: .running,
            startedAtMs: 1_700_000_000_000,
            canStop: false
        )])
    }

    @Test func pendingBashPartCountsAsRunning() {
        let shells = V1AgentShellAdapter.shells(in: transcript([bash("prt-1", status: .pending)]), sessionID: "ses-1")

        #expect(shells.map(\.status) == [.running])
    }

    @Test func otherToolsAreNotShells() {
        let read = MessagePart.tool(ToolPart(id: "prt-2", toolName: "read", status: .running))
        #expect(V1AgentShellAdapter.shells(in: transcript([read]), sessionID: "ses-1").isEmpty)
    }

    @Test func endStatesMapFromThePart() {
        let shells = V1AgentShellAdapter.shells(
            in: transcript([
                bash("prt-1", status: .completed, metadata: ["exit": .int(0)]),
                bash("prt-2", status: .completed, metadata: ["exit": .int(1)]),
                bash("prt-3", status: .completed),
                bash("prt-4", status: .error, interrupted: true),
                bash("prt-5", status: .error),
            ]),
            sessionID: "ses-1"
        )

        #expect(shells.map(\.status) == [.exited(code: 0), .exited(code: 1), .exited(code: nil), .stopped, .failed])
        #expect(shells.allSatisfy { $0.finishedAtMs == 1_700_000_005_000 && !$0.canStop })
    }

    @Test func abortedAndTimedOutCallsEndAsCompletedWithANotice() {
        let aborted = "line 1\n\n<shell_metadata>\nUser aborted the command\n</shell_metadata>"
        let timedOut = "line 1\n\n<shell_metadata>\nshell tool terminated command after exceeding timeout 1000 ms.\n</shell_metadata>"
        let shells = V1AgentShellAdapter.shells(
            in: transcript([
                bash("prt-1", status: .completed, output: aborted, metadata: ["exit": .null]),
                bash("prt-2", status: .completed, output: timedOut, metadata: ["exit": .null]),
                bash("prt-3", status: .completed, output: "User aborted the command\n", metadata: ["exit": .int(0)]),
            ]),
            sessionID: "ses-1"
        )

        #expect(shells.map(\.status) == [.stopped, .timedOut, .exited(code: 0)])
    }

    @Test func runningOutputComesFromMetadataThenTheFinalOutput() {
        let running = V1AgentShellAdapter.output(
            shellID: "prt-1",
            in: transcript([bash("prt-1", status: .running, metadata: ["output": .string("line 1\n")])])
        )
        let done = V1AgentShellAdapter.output(
            shellID: "prt-1",
            in: transcript([bash("prt-1", status: .completed, output: "line 1\nline 2\n", metadata: ["output": .string("line 1\n")])])
        )

        #expect(running == AgentShellOutput(text: "line 1\n", isTruncated: false))
        #expect(done == AgentShellOutput(text: "line 1\nline 2\n", isTruncated: false))
        #expect(V1AgentShellAdapter.output(shellID: "missing", in: transcript([])) == nil)
    }

    @Test func outputAtTheServerCapReadsAsTruncated() {
        let text = String(repeating: "x", count: AgentShellOutput.maxCharacters + 10)
        let output = V1AgentShellAdapter.output(
            shellID: "prt-1",
            in: transcript([bash("prt-1", status: .running, metadata: ["output": .string(text)])])
        )

        #expect(output?.text.count == AgentShellOutput.maxCharacters)
        #expect(output?.isTruncated == true)
    }

    @Test func remoteKeepsAShellSeenRunningAfterItEnds() async {
        let (transcript, continuation) = AsyncStream.makeStream(of: [Message].self)
        var updates = V1AgentShellRemote().shells(sessionID: "ses-1", directory: "/tmp/project", transcript: { transcript })
            .makeAsyncIterator()

        continuation.yield(self.transcript([bash("old", status: .completed), bash("prt-1", status: .running)]))
        let first = await updates.next()
        continuation.yield(self.transcript([bash("old", status: .completed), bash("prt-1", status: .completed, metadata: ["exit": .int(2)])]))
        let second = await updates.next()
        continuation.finish()

        #expect(first?.map(\.id) == ["prt-1"])
        #expect(second?.map(\.status) == [.exited(code: 2)])
    }

    @Test func stopIsUnsupported() async {
        await #expect(throws: AgentShellError.stopUnsupported) {
            try await V1AgentShellRemote().stop(shell: AgentShellFixtures.shell("prt-1"), directory: "/tmp/project")
        }
    }
}

struct V2AgentShellAdapterTests {
    @Test func listedShellMapsAndCanStop() throws {
        let dtos = try JSONDecoder().decode(APIListEnvelope<ShellInfoDTO>.self, from: Data(AgentShellFixtures.listJSON.utf8)).data
        let shell = try #require(dtos.first.flatMap(V2AgentShellAdapter.shell(from:)))

        #expect(shell == AgentShell(
            id: AgentShellFixtures.runningID,
            sessionID: "ses_test",
            command: "for i in $(seq 1 40); do echo line $i; sleep 0.5; done",
            status: .running,
            startedAtMs: 1_793_548_739_990,
            canStop: true
        ))
    }

    @Test func statusMapping() {
        #expect(V2AgentShellAdapter.status(wire: "running", exit: nil) == .running)
        #expect(V2AgentShellAdapter.status(wire: "exited", exit: 3) == .exited(code: 3))
        #expect(V2AgentShellAdapter.status(wire: "exited", exit: nil) == .exited(code: nil))
        #expect(V2AgentShellAdapter.status(wire: "timeout", exit: nil) == .timedOut)
        #expect(V2AgentShellAdapter.status(wire: "killed", exit: nil) == .stopped)
    }

    @Test func listKeepsOnlyRunningShellsOfTheOpenSession() {
        var list = V2AgentShellList(sessionID: "ses_test")
        _ = list.replace(with: [
            AgentShellFixtures.shell("sh_b", startedAtMs: 20),
            AgentShellFixtures.shell("sh_other", session: "ses_other"),
            AgentShellFixtures.shell("sh_a", startedAtMs: 10),
            AgentShellFixtures.shell("sh_done", status: .exited(code: 0)),
        ])

        #expect(list.shells.map(\.id) == ["sh_a", "sh_b"])
    }

    @Test func relistReportsShellsThatEndedWhileAway() {
        var list = V2AgentShellList(sessionID: "ses_test")
        _ = list.replace(with: [
            AgentShellFixtures.shell("sh_a", startedAtMs: 1),
            AgentShellFixtures.shell("sh_b", startedAtMs: 2),
        ])

        let missing = list.replace(with: [AgentShellFixtures.shell("sh_b", startedAtMs: 2)])
        list.restoreEnded(AgentShellFixtures.shell("sh_a", startedAtMs: 1).ended(.exited(code: 0), atMs: 5))

        #expect(missing.map(\.id) == ["sh_a"])
        #expect(list.shells.map(\.status) == [.exited(code: 0), .running])
    }

    @Test func createdEventAddsAShellOfTheOpenSessionOnly() throws {
        let change = try #require(V2AgentShellAdapter.change(from: AgentShellFixtures.event(AgentShellFixtures.createdEvent)))
        var mine = V2AgentShellList(sessionID: "ses_test")
        var other = V2AgentShellList(sessionID: "ses_other")

        let more1 = mine.apply(change)
        #expect(more1)
        let more2 = other.apply(change)
        #expect(!more2)
        #expect(mine.shells.map(\.id) == [AgentShellFixtures.runningID])
        #expect(mine.shells.first?.command == "for i in $(seq 1 40); do echo line $i; sleep 0.5; done")
        #expect(mine.shells.first?.startedAtMs == 1_793_548_739_990)
        #expect(mine.shells.first?.canStop == true)
    }

    @Test func exitedEventsMapTheirStatus() throws {
        let exited = V2AgentShellAdapter.change(from: try AgentShellFixtures.event(AgentShellFixtures.exitedEvent))
        let killed = V2AgentShellAdapter.change(from: try AgentShellFixtures.event(AgentShellFixtures.killedEvent))

        #expect(exited == .ended(id: "sh_197d5ee10001pAROBiMUStdqpO", status: .exited(code: 3), atMs: 1_793_548_742_191))
        #expect(killed == .ended(id: AgentShellFixtures.runningID, status: .stopped, atMs: 1_793_548_743_748))
    }

    @Test func stopThenDeleteEndsTheShellThenRemovesIt() throws {
        var list = V2AgentShellList(sessionID: "ses_test")
        _ = list.replace(with: [AgentShellFixtures.shell(AgentShellFixtures.runningID)])

        list.apply(try #require(V2AgentShellAdapter.change(from: AgentShellFixtures.event(AgentShellFixtures.killedEvent))))
        let ended = list.shells.first
        list.apply(try #require(V2AgentShellAdapter.change(from: AgentShellFixtures.event(AgentShellFixtures.deletedEvent))))

        #expect(ended?.status == .stopped)
        #expect(ended?.canStop == false)
        #expect(ended?.finishedAtMs == 1_793_548_743_748)
        #expect(list.shells.isEmpty)
    }

    @Test func otherEventsAreNoShellChange() throws {
        let event = OpencodeEvent(type: "session.status", sessionID: "ses_test")
        #expect(V2AgentShellAdapter.change(from: event) == nil)
    }

    // MARK: Output tail

    private func page(_ output: String, cursor: Int64, size: Int64, truncated: Bool = false) -> ShellOutputPageDTO {
        ShellOutputPageDTO(output: output, cursor: cursor, size: size, truncated: truncated)
    }

    @Test func shortOutputIsReadFromTheStart() throws {
        var tail = V2AgentShellOutputTail()
        #expect(tail.requestCursor == V2AgentShellOutputTail.probeCursor)

        let more3 = tail.apply(page("", cursor: 35, size: 35))
        #expect(more3)
        #expect(tail.requestCursor == 0)
        let real = try JSONDecoder().decode(APIDataEnvelope<ShellOutputPageDTO>.self, from: Data(AgentShellFixtures.outputJSON.utf8)).data
        let more4 = tail.apply(real)
        #expect(!more4)

        #expect(tail.output == AgentShellOutput(text: "line 1\nline 2\nline 3\nline 4\nline 5\n", isTruncated: false))
        #expect(tail.requestCursor == 35)
    }

    @Test func longOutputStartsAtTheTailAndDropsTheCutLine() throws {
        var tail = V2AgentShellOutputTail()
        let probe = try JSONDecoder().decode(APIDataEnvelope<ShellOutputPageDTO>.self, from: Data(AgentShellFixtures.probeJSON.utf8)).data

        let more5 = tail.apply(probe)
        #expect(more5)
        let start: Int64 = 1_988_895 - V2AgentShellOutputTail.tailBytes
        #expect(tail.requestCursor == start)
        #expect(tail.output.isTruncated)

        let more6 = tail.apply(page("ne 9\nline 10\n", cursor: start + 16_000, size: 1_988_895))
        #expect(more6)
        let more7 = tail.apply(page("line 11\n", cursor: 1_988_895, size: 1_988_895))
        #expect(!more7)

        #expect(tail.output == AgentShellOutput(text: "line 10\nline 11\n", isTruncated: true))
    }

    @Test func pollsContinueFromTheLastCursor() {
        var tail = V2AgentShellOutputTail()
        _ = tail.apply(page("", cursor: 0, size: 0))
        let more8 = tail.apply(page("", cursor: 0, size: 0))
        #expect(!more8)
        let more9 = tail.apply(page("a\n", cursor: 2, size: 2))
        #expect(!more9)
        #expect(tail.requestCursor == 2)
        let more10 = tail.apply(page("b\n", cursor: 4, size: 4))
        #expect(!more10)

        #expect(tail.output.text == "a\nb\n")
    }

    @Test func outputKeepsTheLast30000Characters() {
        var tail = V2AgentShellOutputTail()
        _ = tail.apply(page("", cursor: 0, size: 40_000))
        // The page starts mid-line: everything up to the first newline is dropped.
        let chunk = "cut\n" + String(repeating: "y", count: 39_996)
        _ = tail.apply(page(chunk, cursor: 40_000, size: 40_000))

        #expect(tail.output.text.count == AgentShellOutput.maxCharacters)
        #expect(tail.output.isTruncated)
    }

    @Test func serverTruncationMarksTheOutput() {
        var tail = V2AgentShellOutputTail()
        _ = tail.apply(page("", cursor: 3, size: 3))
        _ = tail.apply(page("ok\n", cursor: 3, size: 3, truncated: true))

        #expect(tail.output.isTruncated)
    }
}

struct V2ServerShellContractTests {
    @Test func shellsDeclaredWhenListRouteExists() throws {
        let features = try V2ServerContract.features(data: Data(#"{"paths":{"/api/shell":{"get":{}}}}"#.utf8))

        #expect(features == V2ServerFeatures(agentShells: true))
        #expect(ServerCapabilities.forProtocol(.v2, v2Features: features).supportsAgentShells)
    }

    @Test func shellsNotDeclaredWithoutListRoute() throws {
        #expect(try !V2ServerContract.features(data: Data(#"{"paths":{"/api/shell/{id}":{"get":{}}}}"#.utf8)).agentShells)
        #expect(!ServerCapabilities.forProtocol(.v2).supportsAgentShells)
        #expect(ServerCapabilities.forProtocol(.v1).supportsAgentShells)
    }
}
