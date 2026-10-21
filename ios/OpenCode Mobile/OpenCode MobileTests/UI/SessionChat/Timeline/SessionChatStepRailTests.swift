import Foundation
import Testing
@testable import OpenCode_Mobile

/// Step rail timeline (#779): block builder, fold rules and liveness.
struct SessionChatStepRailTests {
    // MARK: - Builder

    @Test func railId_staysStableAsStepsAppend() throws {
        let one = try rails([read("r1", status: .running)])
        let two = try rails([read("r1"), grep("g1", status: .running)])
        let three = try rails([read("r1"), grep("g1"), bash("b1", status: .running)])

        #expect(one.count == 1 && two.count == 1 && three.count == 1)
        #expect(one[0].id == "sr:r1")
        #expect(two[0].id == "sr:r1")
        #expect(three[0].id == "sr:r1")
        #expect(three[0].steps.map(\.id) == ["r1", "g1", "b1"])
    }

    @Test func blockId_equalsRailId() {
        let blocks = build([read("r1"), grep("g1")])
        guard case .stepRail(let blockID, let rail)? = blocks.first else {
            Issue.record("Expected a step rail")
            return
        }
        #expect(blockID == rail.id)
        #expect(blockID == PresentationBlockId.stepRail("r1"))
    }

    @Test func text_splitsRails() {
        let blocks = build([read("r1"), text("t1", "Found it."), grep("g1"), bash("b1")])
        #expect(blocks.count == 3)
        guard case .stepRail(_, let first) = blocks[0],
              case .text(_, let narration, _) = blocks[1],
              case .stepRail(_, let second) = blocks[2]
        else {
            Issue.record("Expected rail, text, rail")
            return
        }
        #expect(first.steps.map(\.id) == ["r1"])
        #expect(narration == "Found it.")
        #expect(second.id == "sr:g1")
        #expect(second.steps.map(\.id) == ["g1", "b1"])
    }

    @Test func hiddenParts_areSeeThrough() throws {
        let rails = try rails([
            read("r1"),
            .tool(ToolPart(id: "todo", toolName: "todowrite", status: .completed)),
            .tool(ToolPart(id: "q", toolName: "question", status: .running, input: questionInput)),
            text("empty", "   "),
            .reasoning(ReasoningPart(id: "live", text: "hmm", isStreaming: true)),
            grep("g1"),
        ])
        #expect(rails.count == 1)
        #expect(rails[0].steps.map(\.id) == ["r1", "g1"])
    }

    @Test func settledQuestion_isAStepTitledLikeTheCard() throws {
        let rails = try rails([
            .tool(ToolPart(id: "q", toolName: "question", status: .completed, input: questionInput)),
        ])
        #expect(rails[0].steps.map(\.title) == ["Asked 1 question"])
    }

    @Test func settledQuestion_detailIsTheAnswer() throws {
        let rails = try rails([
            .tool(ToolPart(
                id: "q",
                toolName: "question",
                status: .completed,
                input: questionInput,
                metadata: ["answers": .array([.array([.string("Invoice field")])])]
            )),
        ])
        #expect(rails[0].steps[0].detail == "Invoice field")
    }

    @Test func settledThought_isAStepWithDuration() throws {
        let rails = try rails([
            .reasoning(ReasoningPart(id: "th", text: "Plan", timeStartEpochMs: 1_000, timeEndEpochMs: 3_100)),
            read("r1"),
        ])
        let thought = rails[0].steps[0]
        #expect(thought.kind == .thought)
        #expect(thought.title == "Thought \u{00B7} 2.1s")
        #expect(thought.thoughtText == "Plan")
    }

    @Test func parallelRunningSteps_secondIsBranch() throws {
        let rails = try rails([
            read("r1"),
            bash("b1", status: .running),
            grep("g1", status: .running),
            child("c1", status: .running),
            read("r2", status: .running),
        ])
        #expect(rails[0].steps.map(\.isBranch) == [false, false, true, false, false])
    }

    @Test func thinking_onlyForLiveRailWithNothingRunning() throws {
        let settled = try rails([read("r1"), grep("g1")])[0]
        let running = try rails([read("r1"), bash("b1", status: .running)])[0]

        #expect(settled.isLive == false && settled.thinking == false)
        #expect(settled.withLive(true).thinking)
        #expect(running.withLive(true).isLive)
        #expect(running.withLive(true).thinking == false)
        #expect(settled.withLive(true).withLive(false).thinking == false)
    }

    @Test func worstStatus_failedWhenAnyStepFailed() throws {
        let clean = try rails([read("r1"), grep("g1")])[0]
        let failed = try rails([read("r1"), bash("b1", status: .error, error: "boom")])[0]
        let stopped = try rails([
            .tool(ToolPart(id: "b1", toolName: "bash", status: .error, error: "aborted", input: ["command": .string("ls")], isInterrupted: true)),
        ])[0]

        #expect(clean.worstStatus == .completed)
        #expect(failed.worstStatus == .failed)
        #expect(stopped.worstStatus == .completed)
        #expect(stopped.steps[0].isInterrupted)
    }

    @Test func titles_readGrepEditBashChild() throws {
        let running = try rails([
            read("r1", status: .running),
            grep("g1", status: .running),
            edit("e1", status: .running),
            bash("b1", status: .running),
            child("c1", status: .running),
        ])[0].steps
        #expect(running.map(\.title) == [
            "Reading View.swift",
            "Searching chipTimeline",
            "Editing Blocks.swift",
            "Running npm test",
            "explore",
        ])

        let settled = try rails([read("r1"), grep("g1"), edit("e1"), bash("b1"), child("c1")])[0].steps
        #expect(settled.map(\.title) == [
            "View.swift",
            "chipTimeline",
            "Blocks.swift",
            "Ran npm test",
            "explore",
        ])
        #expect(settled[2].additions == 18)
        #expect(settled[2].deletions == 4)
        #expect(settled[4].kind == .childSession)
        #expect(settled[4].childSession?.childSessionId == "ses-child")
        #expect(settled[4].detail == "Find callers")
    }

    @Test func longCommand_isCutForTheTitle() throws {
        let command = String(repeating: "x", count: 60)
        let steps = try rails([
            .tool(ToolPart(id: "b1", toolName: "bash", status: .running, input: ["command": .string(command)])),
        ])[0].steps
        #expect(steps[0].title == "Running " + String(repeating: "x", count: 40) + "\u{2026}")
    }

    @Test func runningChildSession_detailCountsToolsSoFar() throws {
        let tool = ToolPart(
            id: "c1",
            toolName: "task",
            status: .running,
            input: ["subagent_type": .string("explore"), "description": .string("Find callers")],
            metadata: ["summary": .array([.dictionary([:]), .dictionary([:])])]
        )
        let step = try rails([.tool(tool)])[0].steps[0]
        #expect(step.detail == "Find callers \u{00B7} 2 tools so far")
        #expect(step.childSession?.toolsSoFar == 2)
    }

    @Test func unknownTool_usesTitleThenCapitalisedName() throws {
        let steps = try rails([
            .tool(ToolPart(id: "x1", toolName: "linear_get_issue", status: .completed, title: "OPE-12")),
            .tool(ToolPart(id: "x2", toolName: "linear_get_issue", status: .completed)),
        ])[0].steps
        #expect(steps.map(\.title) == ["OPE-12", "Linear get issue"])
    }

    @Test func invalidTool_isAFailedStepNamedForTheRequestedTool() throws {
        let step = try rails([
            .tool(ToolPart(
                id: "i1",
                toolName: "invalid",
                status: .completed,
                title: "Invalid Tool",
                input: ["tool": .string("edit"), "error": .string("Model tried to call unavailable tool 'edit'.")]
            )),
        ])[0].steps[0]
        #expect(step.title == "Edit")
        #expect(step.detail == "Tool not available")
        #expect(step.status == .failed)
    }

    @Test func toolStep_carriesTheStandardCard() throws {
        let step = try rails([bash("b1")])[0].steps[0]
        #expect(step.card?.toolName == "bash")
        #expect(step.card?.detailText == "ok")
    }

    @Test func toolOnlyMessagesInOneTurn_mergeIntoOneRail() {
        let bodies = [
            body("a1", [read("r1")]),
            body("a2", [grep("g1")]),
            body("a3", [bash("b1", status: .running)]),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: false,
            stepRail: true
        )
        #expect(merged.count == 1)
        guard case .stepRail(_, let rail)? = merged[0].blocks.first, merged[0].blocks.count == 1 else {
            Issue.record("Expected one rail")
            return
        }
        #expect(rail.id == "sr:r1")
        #expect(rail.steps.map(\.id) == ["r1", "g1", "b1"])
    }

    @Test func standardTimeline_isUnchangedWithoutStepRail() {
        let blocks = build([read("r1"), grep("g1")], stepRail: false)
        #expect(blocks.contains { if case .stepRail = $0 { true } else { false } } == false)
    }

    // MARK: - Fold

    @Test func fold_fourSettledSteps_noFold() throws {
        let rail = try rails((1...4).map { read("r\($0)") })[0]
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: false)
        #expect(rows.map(\.id) == ["r1", "r2", "r3", "r4"])
    }

    @Test func fold_fiveSettledSteps_twoEarlierPlusLastThree() throws {
        let rail = try rails((1...5).map { read("r\($0)") })[0]
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: false)
        #expect(rows.first == .fold(count: 2, status: .completed))
        #expect(rows.dropFirst().map(\.id) == ["r3", "r4", "r5"])
    }

    @Test func fold_runningStepsAndChildSessionsNeverFold() throws {
        let rail = try rails([
            read("r1"), read("r2"), child("c1"), read("r3"), read("r4"), read("r5"),
            bash("b1", status: .running),
        ])[0]
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: false)
        #expect(rows.map(\.id) == ["fold", "c1", "r3", "r4", "r5", "b1"])
    }

    @Test func fold_statusIsWorstOfFoldedSteps() throws {
        let rail = try rails([
            bash("b1", status: .error, error: "boom"), read("r2"), read("r3"), read("r4"), read("r5"),
        ])[0]
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: false)
        #expect(rows.first == .fold(count: 2, status: .failed))
    }

    @Test func fold_unfoldedShowsAll() throws {
        let rail = try rails((1...6).map { read("r\($0)") })[0]
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: true)
        #expect(rows.map(\.id) == ["r1", "r2", "r3", "r4", "r5", "r6"])
    }

    @Test func fold_liveRailEndsWithThinking() throws {
        let rail = try rails([read("r1")])[0].withLive(true)
        let rows = SessionChatTimelineBlocks.stepRailRows(rail, unfolded: false)
        #expect(rows.last == .thinking)
    }

    // MARK: - Liveness

    @Test func liveRail_isTheLastRailOfTheLastRowWhileWorking() {
        let rail = SessionChatStepRailBlock(id: "sr:r1", steps: [])
        let rows = [
            row("u1", role: .user, blocks: [.text(blockID: "p:t", text: "hi", renderedMarkdown: nil)]),
            row("a1", role: .assistant, blocks: [.stepRail(blockID: rail.id, rail: rail)]),
        ]
        #expect(SessionChatTimelineBlocks.liveStepRailID(in: rows, sessionWorking: true) == "sr:r1")
        #expect(SessionChatTimelineBlocks.liveStepRailID(in: rows, sessionWorking: false) == nil)
    }

    @Test func liveRail_textAfterRailKeepsTheFooter() {
        let rail = SessionChatStepRailBlock(id: "sr:r1", steps: [])
        let rows = [
            row("a1", role: .assistant, blocks: [
                .stepRail(blockID: rail.id, rail: rail),
                .text(blockID: "p:t", text: "Done", renderedMarkdown: nil),
            ]),
        ]
        #expect(SessionChatTimelineBlocks.liveStepRailID(in: rows, sessionWorking: true) == nil)
    }

    @Test func liveRail_noneWhenLastRowIsTheUser() {
        let rail = SessionChatStepRailBlock(id: "sr:r1", steps: [])
        let rows = [
            row("a1", role: .assistant, blocks: [.stepRail(blockID: rail.id, rail: rail)]),
            row("u2", role: .user, blocks: [.text(blockID: "p:t", text: "next", renderedMarkdown: nil)]),
        ]
        #expect(SessionChatTimelineBlocks.liveStepRailID(in: rows, sessionWorking: true) == nil)
    }

    // MARK: - Helpers

    private let questionInput: [String: AnyCodable] = [
        "questions": .array([.dictionary(["question": .string("Which?")])]),
    ]

    private func build(_ parts: [MessagePart], stepRail: Bool = true) -> [SessionChatTimelineBlock] {
        guard case .ok(let blocks) = SessionChatTimelineBlocks.build(role: .assistant, parts: parts, stepRail: stepRail) else {
            Issue.record("Expected ok")
            return []
        }
        return blocks
    }

    private func rails(_ parts: [MessagePart]) throws -> [SessionChatStepRailBlock] {
        build(parts).compactMap { block in
            if case .stepRail(_, let rail) = block { return rail }
            return nil
        }
    }

    private func text(_ id: String, _ value: String) -> MessagePart {
        .text(TextPart(id: id, text: value, isStreaming: false))
    }

    private func read(_ id: String, status: ToolStatus = .completed) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "read",
            status: status,
            input: ["filePath": .string("/repo/Sources/View.swift")],
            output: status == .completed ? "contents" : nil,
            timeStartEpochMs: 1_000
        ))
    }

    private func grep(_ id: String, status: ToolStatus = .completed) -> MessagePart {
        .tool(ToolPart(id: id, toolName: "grep", status: status, input: ["pattern": .string("chipTimeline")]))
    }

    private func edit(_ id: String, status: ToolStatus = .completed) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "edit",
            status: status,
            input: ["filePath": .string("/repo/Blocks.swift")],
            metadata: status == .completed
                ? ["filediff": .dictionary(["additions": .int(18), "deletions": .int(4)])]
                : nil
        ))
    }

    private func bash(_ id: String, status: ToolStatus = .completed, error: String? = nil) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "bash",
            status: status,
            error: error,
            input: ["command": .string("npm test")],
            output: status == .completed ? "ok" : nil,
            timeStartEpochMs: 2_000
        ))
    }

    private func child(_ id: String, status: ToolStatus = .completed) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "task",
            status: status,
            input: ["subagent_type": .string("explore"), "description": .string("Find callers")],
            metadata: ["sessionId": .string("ses-child")]
        ))
    }

    private func body(_ id: String, _ parts: [MessagePart]) -> DisplayMessageBody {
        let message = Message(id: id, role: .assistant, text: "", parts: parts, model: nil, mode: nil, sentAtEpochMs: nil)
        return DisplayMessageBody(
            message: message,
            isStreaming: false,
            blocks: SessionChatTimelineBlocks.buildOrFallback(
                role: .assistant,
                parts: parts,
                messageId: id,
                fallbackText: "",
                stepRail: true
            ),
            renderedMarkdown: nil
        )
    }

    private func row(_ id: String, role: MessageRole, blocks: [SessionChatTimelineBlock]) -> SessionChatRow {
        SessionChatRow(id: id, role: role, text: "", metadataLabel: "", blocks: blocks)
    }
}
