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

    @Test func withLive_onlyChangesLiveness() throws {
        let settled = try cards([read("r1"), grep("g1")])[0]
        #expect(settled.isLive == false)
        #expect(settled.withLive(true).isLive)
        #expect(settled.withLive(true).steps == settled.steps)
        #expect(settled.withLive(true).withLive(false) == settled)
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

    @Test func sections_splitChildSessionsFromSteps() throws {
        let card = try cards([
            read("r1"),
            bash("b1", status: .running),
            grep("g1"),
            child("c1", status: .running),
            child("c2"),
            .tool(ToolPart(id: "x1", toolName: "bash", status: .error, error: "aborted", input: ["command": .string("ls")], isInterrupted: true)),
        ])[0]
        #expect(card.childSteps.map(\.id) == ["c1", "c2"])
        #expect(card.toolSteps.map(\.id) == ["r1", "b1", "g1", "x1"])
        #expect(card.runningChildren.map(\.id) == ["c1"])
        #expect(card.runningTools.map(\.id) == ["b1"])
    }

    @Test func latestRunningTool_isTheMostRecentlyStartedNonChild() throws {
        let card = try cards([
            bash("b1", status: .running, startedAt: 5_000),
            read("r1", status: .running, startedAt: 9_000),
            grep("g1", status: .running),
            child("c1", status: .running, startedAt: 20_000),
        ])[0]
        // g1 has no start, so it counts as oldest; the child session never counts.
        #expect(card.latestRunningTool?.id == "r1")
        #expect(card.runningTools.count == 3)

        let tie = try cards([bash("b1", status: .running, startedAt: 5_000), bash("b2", status: .running, startedAt: 5_000)])[0]
        #expect(tie.latestRunningTool?.id == "b2")

        let none = try cards([read("r1"), child("c1", status: .running)])[0]
        #expect(none.latestRunningTool == nil)
    }

    // MARK: - Summary

    @Test func summary_countsFamiliesInFirstSeenOrder() throws {
        let steps = try cards([read("r1"), grep("g1"), read("r2"), edit("e1"), read("r3")])[0].steps
        #expect(SessionChatActivityCardBlock.summary(steps) == "5 steps · 3 reads · 1 search · 1 edit")
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
        #expect(SessionChatActivityCardBlock.summary([skill]) == "1 step")
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

    @Test func repaintRows_findTheRowHoldingTheCard() {
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

    // MARK: - Live card presentation

    @Test func liveCard_summaryCountsEveryStep() throws {
        let card = try cards([read("r1"), grep("g1"), bash("b1", status: .running), child("c1", status: .running)])[0]
        let live = SessionChatActivityCardPresentation(card: card.withLive(true), childActions: [:], awaitingUser: false)
        #expect(live.summary == "4 steps · 1 read · 1 search · 1 command · 1 agent")
    }

    @Test func liveCard_agentsOnlyWhileRunning() throws {
        let card = try cards([
            child("c1", status: .running, startedAt: t0 + 10_000, sessionID: "ses-a"),
            child("c2", sessionID: "ses-b"),
        ])[0].withLive(true)
        let live = SessionChatActivityCardPresentation(card: card, childActions: [:], awaitingUser: false)
        #expect(live.agents.map(\.id) == ["c1"])
        #expect(live.agents.first?.agentName == "Explore")
        // Without an observed action the row falls back to the description.
        #expect(live.agents.first?.action == "Find callers")
        #expect(live.agents.first?.startedAtMs == t0 + 10_000)

        let observed = SessionChatActivityCardPresentation(
            card: card,
            childActions: ["ses-a": "Reading View.swift"],
            awaitingUser: false
        )
        #expect(observed.agents.first?.action == "Reading View.swift")
    }

    @Test func liveCard_currentIsTheLatestRunningToolWithExtraCount() throws {
        let card = try cards([
            bash("b1", status: .running, startedAt: t0 + 5_000),
            read("r1", status: .running, startedAt: t0 + 9_000),
            child("c1", status: .running),
        ])[0].withLive(true)
        let live = SessionChatActivityCardPresentation(card: card, childActions: [:], awaitingUser: false)
        #expect(live.current == OpenCodeActivityCurrentRow(
            icon: .read,
            title: "Reading View.swift",
            extraRunning: 1,
            startedAtMs: t0 + 9_000
        ))
        #expect(live.idleText == nil)
    }

    @Test func liveCard_idleTextChoice() throws {
        let waiting = try cards([read("r1"), child("c1", status: .running)])[0].withLive(true)
        let thinking = try cards([read("r1")])[0].withLive(true)

        #expect(SessionChatActivityCardPresentation(card: waiting, childActions: [:], awaitingUser: false).idleText
            == "Waiting for agents")
        #expect(SessionChatActivityCardPresentation(card: thinking, childActions: [:], awaitingUser: false).idleText
            == "Thinking…")
        #expect(SessionChatActivityCardPresentation(card: thinking, childActions: [:], awaitingUser: true).idleText
            == "Waiting for you")
        #expect(SessionChatActivityCardPresentation(card: thinking, childActions: [:], awaitingUser: false).current == nil)
    }

    @Test func liveCard_stateCarriesTheRunStart() throws {
        let card = try cards([read("r1", startedAt: t0 + 3_000), bash("b1", status: .running, startedAt: t0 + 4_000)])[0]
        let live = SessionChatActivityCardPresentation(card: card.withLive(true), childActions: [:], awaitingUser: false)
        #expect(live.state == .live(startedAtMs: t0 + 3_000))
    }

    // MARK: - Settled line

    @Test func settledLine_namesDedupedInFirstSeenOrder() throws {
        let card = try cards([
            read("r1"),
            edit("e1"),
            read("r2"),
            grep("g1"),
            .tool(ToolPart(id: "gl", toolName: "glob", status: .completed, input: ["pattern": .string("*.swift")])),
            .tool(ToolPart(id: "w1", toolName: "write", status: .completed, input: ["filePath": .string("/repo/a.txt")])),
            .tool(ToolPart(id: "f1", toolName: "webfetch", status: .completed, input: ["url": .string("https://a.dev")])),
            .tool(ToolPart(id: "ws", toolName: "websearch", status: .completed, input: ["query": .string("swift")])),
            bash("b1"),
            child("c1"),
            .tool(ToolPart(id: "s1", toolName: "skill", status: .completed, input: ["name": .string("feature")])),
            .reasoning(ReasoningPart(id: "th", text: "Plan", timeStartEpochMs: 1_000, timeEndEpochMs: 2_000)),
            .tool(ToolPart(id: "m1", toolName: "lint_files", status: .completed)),
            edit("e2"),
        ])[0]
        let settled = SessionChatActivityCardPresentation(card: card, childActions: [:], awaitingUser: false)
        #expect(settled.summary
            == "Read, Edit, Grep, Glob, Write, Fetch, Search, Shell, Agent, Skill, Thought, Lint_files")
        #expect(settled.state == .settled(worstStatus: .completed, stepCount: 14))
        #expect(settled.agents.isEmpty && settled.current == nil && settled.idleText == nil)
    }

    @Test func settledLine_worstStatusIgnoresInterrupted() throws {
        let failed = try cards([read("r1"), bash("b1", status: .error, error: "boom")])[0]
        let stopped = try cards([
            read("r1"),
            .tool(ToolPart(id: "b1", toolName: "bash", status: .error, error: "aborted", input: ["command": .string("ls")], isInterrupted: true)),
        ])[0]
        #expect(SessionChatActivityCardPresentation(card: failed, childActions: [:], awaitingUser: false).state
            == .settled(worstStatus: .failed, stepCount: 2))
        #expect(SessionChatActivityCardPresentation(card: stopped, childActions: [:], awaitingUser: false).state
            == .settled(worstStatus: .completed, stepCount: 2))
    }

    // MARK: - Sheet

    @Test func sheet_resolvesTheCardByIdFromCurrentRows() throws {
        let first = try cards([read("r1")])[0]
        let grown = try cards([read("r1"), grep("g1", status: .running)])[0]
        let rows = [
            row("a1", role: .assistant, blocks: [.activityCard(blockID: first.id, card: first)]),
            row("u2", role: .user, blocks: [.text(blockID: "p:t", text: "next", renderedMarkdown: nil)]),
            row("a2", role: .assistant, blocks: [
                .text(blockID: "p:x", text: "Looking", renderedMarkdown: nil),
                .activityCard(blockID: "ac:other", card: SessionChatActivityCardBlock(id: "ac:other", steps: [])),
            ]),
        ]
        let resolved = SessionChatActivityCardPresentation.card(id: "ac:r1", in: rows)
        #expect(resolved?.rowID == "a1")
        #expect(resolved?.card.steps.map(\.id) == ["r1"])

        // The same id in a newer frame resolves to the grown card.
        let updated = [row("a1", role: .assistant, blocks: [.activityCard(blockID: grown.id, card: grown)])]
        #expect(SessionChatActivityCardPresentation.card(id: "ac:r1", in: updated)?.card.steps.map(\.id) == ["r1", "g1"])
        #expect(SessionChatActivityCardPresentation.card(id: "ac:gone", in: updated) == nil)
    }

    @Test func sheet_childCaption() throws {
        let card = try cards([
            child("c1", status: .running),
            child("c2"),
            .tool(ToolPart(
                id: "c3",
                toolName: "task",
                status: .error,
                error: "Child crashed",
                input: ["subagent_type": .string("explore"), "description": .string("Find callers")]
            )),
        ])[0]
        let observed = SessionChatChildActivity(currentAction: nil, recentSteps: [], toolCount: 4)
        #expect(SessionChatActivitySheetContent.childCaption(card.steps[0], activity: observed) == "4 tools so far")
        #expect(SessionChatActivitySheetContent.childCaption(card.steps[0], activity: nil) == nil)
        #expect(SessionChatActivitySheetContent.childCaption(card.steps[1], activity: nil) == nil)
        #expect(SessionChatActivitySheetContent.childCaption(card.steps[2], activity: nil) == "Child crashed")
    }

    // MARK: - Child session activity

    @Test func childActivity_currentActionAndRecentSteps() {
        let messages = [
            Message(id: "u", role: .user, text: "go", parts: [], model: nil, mode: nil, sentAtEpochMs: nil),
            Message(
                id: "m1",
                role: .assistant,
                text: "",
                parts: [read("r1"), grep("g1"), bash("b1", status: .running, startedAt: 3_000)],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
            Message(
                id: "m2",
                role: .assistant,
                text: "",
                parts: [read("r2", status: .running, startedAt: 2_000), .tool(ToolPart(id: "todo", toolName: "todowrite", status: .completed))],
                model: nil,
                mode: nil,
                sentAtEpochMs: nil
            ),
        ]
        let activity = SessionChatTimelineBlocks.childActivity(from: messages)
        // b1 started last; hidden tools are not steps.
        #expect(activity.currentAction == "Running npm test")
        #expect(activity.toolCount == 4)
        #expect(activity.recentSteps == [
            SessionChatChildActivityStep(id: "g1", title: "chipTimeline", isRunning: false),
            SessionChatChildActivityStep(id: "b1", title: "Running npm test", isRunning: true),
            SessionChatChildActivityStep(id: "r2", title: "View.swift", isRunning: true),
        ])
        #expect(SessionChatTimelineBlocks.childActivity(from: []) == .empty)
    }

    @Test func childActions_onlyForTheLiveCardsRunningChildren() throws {
        let card = try cards([
            child("c1", status: .running, sessionID: "ses-a"),
            child("c2", sessionID: "ses-b"),
        ])[0]
        let rows = [row("a1", role: .assistant, blocks: [.activityCard(blockID: card.id, card: card)])]
        let activities = [
            "ses-a": SessionChatChildActivity(currentAction: "Reading View.swift", recentSteps: [], toolCount: 1),
            "ses-b": SessionChatChildActivity(currentAction: "Done", recentSteps: [], toolCount: 2),
        ]
        #expect(SessionChatActivityCardPresentation.childActions(in: rows, liveCardID: card.id, activities: activities)
            == ["ses-a": "Reading View.swift"])
        #expect(SessionChatActivityCardPresentation.childActions(in: rows, liveCardID: nil, activities: activities)
            .isEmpty)
    }

    // MARK: - Helpers

    /// A real epoch in ms, so `ToolStartTime.normalizeEpochMs` keeps it.
    private let t0: Int64 = 1_760_000_000_000

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

    private func read(_ id: String, status: ToolStatus = .completed, startedAt: Int64 = 1_000) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "read",
            status: status,
            input: ["filePath": .string("/repo/Sources/View.swift")],
            output: status == .completed ? "contents" : nil,
            timeStartEpochMs: startedAt
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

    private func bash(
        _ id: String,
        status: ToolStatus = .completed,
        error: String? = nil,
        startedAt: Int64 = 2_000
    ) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "bash",
            status: status,
            error: error,
            input: ["command": .string("npm test")],
            output: status == .completed ? "ok" : nil,
            timeStartEpochMs: startedAt
        ))
    }

    private func child(
        _ id: String,
        status: ToolStatus = .completed,
        startedAt: Int64? = nil,
        sessionID: String = "ses-child"
    ) -> MessagePart {
        .tool(ToolPart(
            id: id,
            toolName: "task",
            status: status,
            input: ["subagent_type": .string("explore"), "description": .string("Find callers")],
            metadata: ["sessionId": .string(sessionID)],
            timeStartEpochMs: startedAt
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
