import Foundation
import Testing
@testable import OpenCode_Mobile

/// Activity card footer hold and step entry debounce (#779).
@MainActor
struct SessionChatActivityFooterHoldTests {
    private func tool(_ id: String, _ status: SessionChatStepStatus = .completed, startedAtMs: Int64? = nil) -> SessionChatStep {
        SessionChatStep(
            id: id,
            kind: .tool,
            status: status,
            title: id,
            runningTitle: "Running \(id)",
            settledTitle: "Ran \(id)",
            family: .command,
            startedAtMs: startedAtMs
        )
    }

    private func agent(_ id: String) -> SessionChatStep {
        SessionChatStep(id: id, kind: .childSession, status: .running, title: "explore", family: .agent)
    }

    private let thinking = SessionChatActivityFooter.idle(SessionChatActivityFooter.thinking)

    private func running(_ id: String, extra: Int = 0, startedAtMs: Int64? = nil) -> SessionChatActivityFooter {
        .tool(.init(stepID: id, icon: .command, title: "Running \(id)", running: true, extraRunning: extra, startedAtMs: startedAtMs))
    }

    private func settled(_ id: String) -> SessionChatActivityFooter {
        .tool(.init(stepID: id, icon: .command, title: "Ran \(id)", running: false, extraRunning: 0, startedAtMs: nil))
    }

    @Test func firstValue_showsImmediately() {
        var hold = SessionChatActivityFooterHold()
        #expect(hold.submit([tool("a", .running, startedAtMs: 5)], nowMs: 0) == running("a", startedAtMs: 5))
        #expect(hold.expiresAtMs == 1_200)
    }

    @Test func changeInsideHold_waitsAndLatestPendingWins() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([], nowMs: 0)
        #expect(hold.submit([tool("a", .running)], nowMs: 100) == thinking)
        #expect(hold.submit([tool("a"), tool("b", .running)], nowMs: 200) == thinking)
        #expect(hold.pending == running("b"))
        #expect(hold.expire(nowMs: 1_200) == running("b"))
        #expect(hold.expiresAtMs == 2_400)
    }

    @Test func shownToolSettles_turnsSettledInPlaceWithoutRestartingHold() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([tool("a", .running)], nowMs: 0)
        #expect(hold.submit([tool("a")], nowMs: 300) == settled("a"))
        #expect(hold.expiresAtMs == 1_200)
        #expect(hold.expire(nowMs: 1_200) == thinking)
    }

    @Test func expiry_appliesPendingAndRestartsHold() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([tool("a", .running)], nowMs: 0)
        hold.submit([tool("a", .running), tool("b", .running, startedAtMs: 9)], nowMs: 400)
        #expect(hold.expire(nowMs: 1_199) == running("a"))
        #expect(hold.expire(nowMs: 1_200) == running("b", extra: 1, startedAtMs: 9))
        #expect(hold.expiresAtMs == 2_400)
    }

    @Test func catchUp_showsSkippedToolOnceSettledThenIdle() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([tool("a", .running, startedAtMs: 1)], nowMs: 0)
        hold.submit([tool("a", startedAtMs: 1), tool("b", .running, startedAtMs: 2)], nowMs: 100)
        hold.submit([tool("a", startedAtMs: 1), tool("b", startedAtMs: 2), tool("c", .running, startedAtMs: 3)], nowMs: 200)
        let steps = [tool("a", startedAtMs: 1), tool("b", startedAtMs: 2), tool("c", startedAtMs: 3)]
        #expect(hold.submit(steps, nowMs: 300) == settled("a"))
        // b and c ran and finished unseen: the newest, c, shows settled for one hold.
        #expect(hold.expire(nowMs: 1_200) == settled("c"))
        #expect(hold.expiresAtMs == 2_400)
        // One catch-up per hold: b is skipped and the footer falls to idle.
        #expect(hold.expire(nowMs: 2_400) == thinking)
        #expect(hold.expire(nowMs: 3_600) == thinking)
        #expect(hold.expiresAtMs == nil)
    }

    @Test func toolStartingAndFinishingWhileIdle_isShownSettledAfterHold() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([], nowMs: 0)
        #expect(hold.submit([tool("a")], nowMs: 100) == thinking)
        #expect(hold.expire(nowMs: 1_200) == settled("a"))
        #expect(hold.expire(nowMs: 2_400) == thinking)
    }

    @Test func equalDesired_isNoOp() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([tool("a", .running)], nowMs: 0)
        #expect(hold.submit([tool("a", .running)], nowMs: 500) == running("a"))
        #expect(hold.pending == nil)
        #expect(hold.expiresAtMs == 1_200)
        #expect(hold.expire(nowMs: 1_200) == running("a"))
        #expect(hold.expiresAtMs == nil)
        // A later change with no hold running shows at once.
        hold.submit([tool("a"), tool("b", .running)], nowMs: 5_000)
        #expect(hold.displayed == running("b"))
    }

    @Test func shownTool_picksUpParallelCountInPlace() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([tool("b", .running, startedAtMs: 9)], nowMs: 0)
        let parallel = [tool("a", .running, startedAtMs: 1), tool("b", .running, startedAtMs: 9)]
        #expect(hold.submit(parallel, nowMs: 100) == running("b", extra: 1, startedAtMs: 9))
        #expect(hold.expiresAtMs == 1_200)
    }

    @Test func runningChildren_holdWaitingForAgents() {
        var hold = SessionChatActivityFooterHold()
        hold.submit([], nowMs: 0)
        hold.submit([agent("c1")], nowMs: 100)
        #expect(hold.expire(nowMs: 1_200) == .idle("Waiting for agents"))
    }

    @Test func settledSegment_dropsHoldAndNewOneStartsFresh() {
        // The region drops a card's hold once it is no longer trailing; a new hold starts at rule 1.
        var fresh = SessionChatActivityFooterHold()
        #expect(fresh.submit([tool("a")], nowMs: 100) == thinking)
        #expect(fresh.pending == nil)
    }

    // MARK: - Entry debounce

    @Test func debounce_hidesStepUntilItRanFor300Ms() {
        var debounce = SessionChatStepEntryDebounce()
        #expect(debounce.submit([], nowMs: 0).isEmpty)
        #expect(debounce.submit([tool("a", .running, startedAtMs: 0)], nowMs: 1_000) == ["a"])
        #expect(debounce.nextDueMs == 1_300)
        #expect(debounce.submit([tool("a", .running, startedAtMs: 0)], nowMs: 1_299) == ["a"])
        #expect(debounce.submit([tool("a", .running, startedAtMs: 0)], nowMs: 1_300).isEmpty)
        #expect(debounce.nextDueMs == nil)
    }

    @Test func debounce_stepSettlingWithin300Ms_appearsSettled() {
        var debounce = SessionChatStepEntryDebounce()
        debounce.submit([], nowMs: 0)
        #expect(debounce.submit([tool("a", .running)], nowMs: 100) == ["a"])
        #expect(debounce.submit([tool("a")], nowMs: 200).isEmpty)
        #expect(debounce.nextDueMs == nil)
    }

    @Test func debounce_firstBuild_showsStepsServerStartedLongAgo() {
        var debounce = SessionChatStepEntryDebounce()
        let steps = [tool("old", .running, startedAtMs: 10_000), tool("new", .running, startedAtMs: 19_900), tool("unknown", .running)]
        #expect(debounce.submit(steps, nowMs: 20_000) == ["new", "unknown"])
        // Later steps always wait, whatever the server says.
        #expect(debounce.submit(steps + [tool("late", .running, startedAtMs: 1_000)], nowMs: 20_300) == ["late"])
    }

    @Test func debounce_neverHidesChildSessions() {
        var debounce = SessionChatStepEntryDebounce()
        debounce.submit([], nowMs: 0)
        #expect(debounce.submit([agent("c1")], nowMs: 10).isEmpty)
    }

    @Test func debouncedToolThatFinishes_isCaughtUpSettledByFooter() {
        var debounce = SessionChatStepEntryDebounce()
        var hold = SessionChatActivityFooterHold()
        func submit(_ steps: [SessionChatStep], _ now: Int64) -> SessionChatActivityFooter {
            let hidden = debounce.submit(steps, nowMs: now)
            return hold.submit(steps.filter { !hidden.contains($0.id) }, nowMs: now)
        }
        #expect(submit([], 0) == thinking)
        // Hidden while it runs, so the footer never shows "Running a".
        #expect(submit([tool("a", .running)], 100) == thinking)
        #expect(submit([tool("a")], 200) == thinking)
        #expect(submit([tool("a")], 1_200) == settled("a"))
    }

    @Test func rail_hidingDropsStepsRecomputesBranchesAndKeepsThinking() {
        let rail = SessionChatStepRailBlock(
            id: "sr:a",
            steps: [tool("a"), tool("b", .running), tool("c", .running).withBranch(true)],
            isLive: true
        )
        #expect(!rail.thinking)
        let onlyA = rail.hiding(["b", "c"])
        #expect(onlyA.steps.map(\.id) == ["a"])
        #expect(onlyA.thinking)
        let withC = rail.hiding(["b"])
        #expect(withC.steps.map(\.id) == ["a", "c"])
        #expect(withC.steps.map(\.isBranch) == [false, false])
        #expect(!withC.thinking)
        #expect(rail.hiding([]) == rail)
    }

    // MARK: - Presentation

    @Test func presentation_settledFooterRow_hasNoCountOrElapsed() {
        let card = SessionChatActivityCardBlock(id: "ac:a", steps: [tool("a"), tool("b", .running)], isLive: true)
        let presentation = SessionChatActivityCardPresentation(
            card: card,
            childActions: [:],
            awaitingUser: false,
            footer: settled("a")
        )
        #expect(presentation.current == OpenCodeActivityCurrentRow(
            icon: .command,
            title: "Ran a",
            extraRunning: 0,
            startedAtMs: nil,
            running: false,
            stepID: "a"
        ))
        #expect(presentation.idleText == nil)
    }

    @Test func presentation_heldIdle_mapsThinkingToWaitingForYou() {
        let card = SessionChatActivityCardBlock(id: "ac:a", steps: [tool("a")], isLive: true)
        let waiting = SessionChatActivityCardPresentation(card: card, childActions: [:], awaitingUser: true, footer: thinking)
        #expect(waiting.current == nil)
        #expect(waiting.idleText == "Waiting for you")
        let held = SessionChatActivityCardPresentation(card: card, childActions: [:], awaitingUser: false, footer: thinking)
        #expect(held.idleText == SessionChatActivityFooter.thinking)
    }
}
