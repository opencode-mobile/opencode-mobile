import Foundation
import Testing
@testable import OpenCode_Mobile

/// Activity card timeline (#779): block builder, summary strings and liveness.
struct SessionChatActivityCardTests {
    // MARK: - Builder

    @Test func cardId_staysStableAsStepsAppend() throws {
        let one = try cards([read("r1", status: .running)])
        let two = try cards([read("r1"), grep("g1", status: .running)])
        let three = try cards([read("r1"), grep("g1"), bash("b1", status: .running)])

        #expect(one.count == 1 && two.count == 1 && three.count == 1)
        #expect(one[0].id == "ac:r1")
        #expect(two[0].id == "ac:r1")
        #expect(three[0].id == "ac:r1")
        #expect(three[0].steps.map(\.id) == ["r1", "g1", "b1"])
    }

    @Test func blockId_equalsCardId() {
        let blocks = build([read("r1"), grep("g1")])
        guard case .activityCard(let blockID, let card)? = blocks.first else {
            Issue.record("Expected an activity card")
            return
        }
        #expect(blockID == card.id)
        #expect(blockID == PresentationBlockId.activityCard("r1"))
    }

    @Test func text_splitsCards() {
        let blocks = build([read("r1"), text("t1", "Found it."), grep("g1"), bash("b1")])
        #expect(blocks.count == 3)
        guard case .activityCard(_, let first) = blocks[0],
              case .text(_, let narration, _) = blocks[1],
              case .activityCard(_, let second) = blocks[2]
        else {
            Issue.record("Expected card, text, card")
            return
        }
        #expect(first.steps.map(\.id) == ["r1"])
        #expect(narration == "Found it.")
        #expect(second.id == "ac:g1")
        #expect(second.steps.map(\.id) == ["g1", "b1"])
    }

    @Test func hiddenParts_areSeeThrough() throws {
        let cards = try cards([
            read("r1"),
            .tool(ToolPart(id: "todo", toolName: "todowrite", status: .completed)),
            .tool(ToolPart(id: "q", toolName: "question", status: .running, input: questionInput)),
            text("empty", "   "),
            .reasoning(ReasoningPart(id: "live", text: "hmm", isStreaming: true)),
            grep("g1"),
        ])
        #expect(cards.count == 1)
        #expect(cards[0].steps.map(\.id) == ["r1", "g1"])
    }

    @Test func toolOnlyMessagesInOneTurn_mergeIntoOneCard() {
        let bodies = [
            body("a1", [read("r1")]),
            body("a2", [grep("g1")]),
            body("a3", [bash("b1", status: .running)]),
        ]
        let merged = SessionChatTimelineBlocks.mergeConsecutiveToolOnlyAssistantBodies(
            bodies,
            collapseConsecutiveToolCalls: false,
            activityCard: true
        )
        #expect(merged.count == 1)
        guard case .activityCard(_, let card)? = merged[0].blocks.first, merged[0].blocks.count == 1 else {
            Issue.record("Expected one card")
            return
        }
        #expect(card.id == "ac:r1")
        #expect(card.steps.map(\.id) == ["r1", "g1", "b1"])
    }

    @Test func thinking_onlyForLiveCardWithNothingRunning() throws {
        let settled = try cards([read("r1"), grep("g1")])[0]
        let running = try cards([read("r1"), bash("b1", status: .running)])[0]

        #expect(settled.isLive == false && settled.thinking == false)
        #expect(settled.withLive(true).thinking)
        #expect(running.withLive(true).isLive)
        #expect(running.withLive(true).thinking == false)
        #expect(settled.withLive(true).withLive(false).thinking == false)
    }

    @Test func titles_runningKeepTheVerbSettledDropIt() throws {
        let running = try cards([
            read("r1", status: .running),
            grep("g1", status: .running),
            edit("e1", status: .running),
            bash("b1", status: .running),
        ])[0].steps
        #expect(running.map(\.runningTitle) == [
            "Reading View.swift",
            "Searching chipTimeline",
            "Editing Blocks.swift",
            "Running npm test",
        ])

        let settled = try cards([read("r1"), grep("g1"), edit("e1"), bash("b1")])[0].steps
        #expect(settled.map(\.settledTitle) == ["View.swift", "chipTimeline", "Blocks.swift", "Ran npm test"])
        #expect(settled.map(\.runningTitle) == [
            "Reading View.swift",
            "Searching chipTimeline",
            "Editing Blocks.swift",
            "Running npm test",
        ])
        #expect(settled[2].additions == 18 && settled[2].deletions == 4)
    }

    @Test func families_perTool() throws {
        let steps = try cards([
            read("r1"),
            grep("g1"),
            .tool(ToolPart(id: "w1", toolName: "webfetch", status: .completed, input: ["url": .string("https://a.dev/x")])),
            edit("e1"),
            bash("b1"),
            child("c1"),
            .reasoning(ReasoningPart(id: "th", text: "Plan", timeStartEpochMs: 1_000, timeEndEpochMs: 2_000)),
            .tool(ToolPart(id: "s1", toolName: "skill", status: .completed, input: ["name": .string("feature")])),
        ])[0].steps
        #expect(steps.map(\.family) == [.read, .search, .fetch, .edit, .command, .agent, .thought, nil])
    }

    @Test func settledStep_keepsItsStartForTheCardElapsed() throws {
        let card = try cards([read("r1"), bash("b1")])[0]
        #expect(card.steps[0].startedAtMs == 1_000)
        #expect(card.startedAtMs == 1_000)
    }

    @Test func runningAndDone_splitInOrder() throws {
        let card = try cards([
            read("r1"),
            bash("b1", status: .running),
            grep("g1"),
            child("c1", status: .running),
            .tool(ToolPart(id: "x1", toolName: "bash", status: .error, error: "aborted", input: ["command": .string("ls")], isInterrupted: true)),
        ])[0]
        #expect(card.running.map(\.id) == ["b1", "c1"])
        #expect(card.done.map(\.id) == ["r1", "g1", "x1"])
    }

    // MARK: - Summary

    @Test func summary_emptyDone() {
        #expect(SessionChatActivityCardBlock.summary(done: []) == "nothing finished yet")
    }

    @Test func summary_countsFamiliesInFirstSeenOrder() throws {
        let steps = try cards([read("r1"), grep("g1"), read("r2"), edit("e1"), read("r3")])[0].steps
        #expect(SessionChatActivityCardBlock.summary(done: steps) == "5 done · 3 reads · 1 search · 1 edit")
        #expect(SessionChatActivityCardBlock.settledSummary(steps) == "5 steps · 3 reads · 1 search · 1 edit")
    }

    @Test func summary_singularAndPlural() {
        func steps(_ families: [SessionChatStepFamily]) -> [SessionChatStep] {
            families.enumerated().map { index, family in
                SessionChatStep(id: "s\(index)", kind: .tool, status: .completed, title: "x", family: family)
            }
        }
        #expect(SessionChatActivityCardBlock.families(steps([.search, .search])) == "2 searches")
        #expect(SessionChatActivityCardBlock.families(steps([.fetch, .fetch])) == "2 fetches")
        #expect(SessionChatActivityCardBlock.families(steps([.command])) == "1 command")
        #expect(SessionChatActivityCardBlock.families(steps([.agent, .agent])) == "2 agents")
        #expect(SessionChatActivityCardBlock.families(steps([.thought])) == "1 thought")
        #expect(SessionChatActivityCardBlock.families(steps([.fetch])) == "1 fetch")
        #expect(SessionChatActivityCardBlock.families(steps([.edit, .edit])) == "2 edits")
    }

    @Test func summary_stepsOutsideFamiliesCountButAreNotListed() {
        let skill = SessionChatStep(id: "s1", kind: .tool, status: .completed, title: "Skill feature")
        #expect(SessionChatActivityCardBlock.settledSummary([skill]) == "1 step")
        #expect(SessionChatActivityCardBlock.summary(done: [skill]) == "1 done")
    }

    // MARK: - Worst status

    @Test func worstStatus_failedWhenAnyStepFailedNotInterrupted() throws {
        let clean = try cards([read("r1"), grep("g1")])[0]
        let failed = try cards([read("r1"), bash("b1", status: .error, error: "boom")])[0]
        let stopped = try cards([
            .tool(ToolPart(id: "b1", toolName: "bash", status: .error, error: "aborted", input: ["command": .string("ls")], isInterrupted: true)),
        ])[0]

        #expect(clean.worstStatus == .completed)
        #expect(failed.worstStatus == .failed)
        #expect(stopped.worstStatus == .completed)
    }

    // MARK: - Liveness

    @Test func liveCard_isTheLastCardOfTheLastRowWhileWorking() {
        let card = SessionChatActivityCardBlock(id: "ac:r1", steps: [])
        let rows = [
            row("u1", role: .user, blocks: [.text(blockID: "p:t", text: "hi", renderedMarkdown: nil)]),
            row("a1", role: .assistant, blocks: [.activityCard(blockID: card.id, card: card)]),
        ]
        #expect(SessionChatTimelineBlocks.liveStepRunID(in: rows, sessionWorking: true) == "ac:r1")
        #expect(SessionChatTimelineBlocks.liveStepRunID(in: rows, sessionWorking: false) == nil)
    }

    @Test func liveCard_textAfterCardKeepsTheFooter() {
        let card = SessionChatActivityCardBlock(id: "ac:r1", steps: [])
        let rows = [
            row("a1", role: .assistant, blocks: [
                .activityCard(blockID: card.id, card: card),
                .text(blockID: "p:t", text: "Done", renderedMarkdown: nil),
            ]),
        ]
        #expect(SessionChatTimelineBlocks.liveStepRunID(in: rows, sessionWorking: true) == nil)
    }

    @Test func liveCard_staysLiveWhileTheTurnWaitsOnTheUser() {
        let card = SessionChatActivityCardBlock(id: "ac:r1", steps: [])
        let rows = [row("a1", role: .assistant, blocks: [.activityCard(blockID: card.id, card: card)])]
        // The Working footer is hidden during an ask, so it names no live run.
        #expect(SessionChatTimelineBlocks.liveActivityCardID(in: rows, footerLiveID: nil, isTurnActive: true) == "ac:r1")
        #expect(SessionChatTimelineBlocks.liveActivityCardID(in: rows, footerLiveID: nil, isTurnActive: false) == nil)
        #expect(SessionChatTimelineBlocks.liveActivityCardID(in: rows, footerLiveID: "ac:r1", isTurnActive: false) == "ac:r1")
    }

    @Test func expansionRows_findTheRowHoldingTheCard() {
        let card = SessionChatActivityCardBlock(id: "ac:r1", steps: [])
        let rows = [
            row("u1", role: .user, blocks: [.text(blockID: "p:t", text: "hi", renderedMarkdown: nil)]),
            row("a1", role: .assistant, blocks: [.activityCard(blockID: card.id, card: card)]),
        ]
        #expect(SessionChatStepParagraphExpansion.rowIDs(containingStepRails: ["ac:r1"], in: rows) == ["a1"])
    }

    @Test func otherStyles_areUnchangedWithoutActivityCard() {
        guard case .ok(let standard) = SessionChatTimelineBlocks.build(role: .assistant, parts: [read("r1"), grep("g1")]),
              case .ok(let rail) = SessionChatTimelineBlocks.build(role: .assistant, parts: [read("r1")], stepRail: true)
        else {
            Issue.record("Expected ok")
            return
        }
        #expect(standard.contains { if case .activityCard = $0 { true } else { false } } == false)
        #expect(rail.allSatisfy { if case .stepRail = $0 { true } else { false } })
    }

    // MARK: - Helpers

    private let questionInput: [String: AnyCodable] = [
        "questions": .array([.dictionary(["question": .string("Which?")])]),
    ]

    private func build(_ parts: [MessagePart]) -> [SessionChatTimelineBlock] {
        guard case .ok(let blocks) = SessionChatTimelineBlocks.build(role: .assistant, parts: parts, activityCard: true)
        else {
            Issue.record("Expected ok")
            return []
        }
        return blocks
    }

    private func cards(_ parts: [MessagePart]) throws -> [SessionChatActivityCardBlock] {
        build(parts).compactMap { block in
            if case .activityCard(_, let card) = block { return card }
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
                activityCard: true
            ),
            renderedMarkdown: nil
        )
    }

    private func row(_ id: String, role: MessageRole, blocks: [SessionChatTimelineBlock]) -> SessionChatRow {
        SessionChatRow(id: id, role: role, text: "", metadataLabel: "", blocks: blocks)
    }
}
