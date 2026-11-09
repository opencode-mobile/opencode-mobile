import Foundation
import Testing
@testable import OpenCode_Mobile

/// What Session Chat shows for a goal: activity and wait, the pill, copy, the status row's
/// place, the top-left slot with three pills, and the `/goal` draft rules.
@MainActor
struct SessionChatGoalUiTests {
    // MARK: Activity

    @Test func activity_workingWhileTheSessionRuns() throws {
        let state = try #require(resolve(goal(.active), busy: true))
        #expect(state.activity == .working)
        #expect(state.wait == nil)
        #expect(state.pill.floatingPillLayout == .counter(label: "Goal"))
        #expect(state.pill.floatingPillState == .live)
    }

    @Test func activity_waitingOnARequestOrBackgroundWork() throws {
        #expect(resolve(goal(.active), busy: true, permission: true)?.wait == .permission)
        #expect(resolve(goal(.active), question: true)?.wait == .question)
        #expect(resolve(goal(.active), shell: "./gradlew connectedCheck")?.wait == .shell(command: "./gradlew connectedCheck"))
        #expect(resolve(goal(.active), subagent: true)?.wait == .subagent)
        // A running turn outranks background work.
        #expect(resolve(goal(.active), busy: true, shell: "make")?.wait == nil)
        let waiting = try #require(resolve(goal(.active), permission: true))
        #expect(waiting.activity == .waiting)
        #expect(waiting.pill.floatingPillLayout == .iconLabel(icon: .radioButtonChecked, label: "Goal"))
        #expect(waiting.pill.floatingPillState == .ready)
    }

    @Test func activity_pausedNeedsAttention_metIsDone() throws {
        let paused = try #require(resolve(goal(.paused, reason: "Paused by the user"), shell: "make"))
        #expect(paused.activity == .paused)
        #expect(paused.wait == nil)
        #expect(paused.pill.floatingPillLayout == .iconLabel(icon: .pause, label: "Goal paused"))
        #expect(paused.pill.floatingPillState == .needsAttention)
        let met = try #require(resolve(goal(.complete, reason: "All tests pass")))
        #expect(met.pill.floatingPillLayout == .iconLabel(icon: .check, label: "Goal met"))
        #expect(met.pill.floatingPillState == .done)
    }

    @Test func noGoal_orASubagentSession_showsNothing() {
        #expect(resolve(nil) == nil)
        #expect(resolve(goal(.active), child: true) == nil)
    }

    @Test func stale_dimsAndTurnsActionsOff() throws {
        var region = SessionChatGoalRegionState(goal: goal(.active))
        region.isStale = true
        let state = try #require(SessionChatGoalUiState.resolve(
            region: region,
            isChildSession: false,
            isSessionBusy: true,
            hasPermission: false,
            hasQuestion: false,
            runningShellCommand: nil,
            hasRunningSubagent: false,
            canMutate: true
        ))
        #expect(!state.actionsEnabled)
        #expect(state.pill.isStale)
        // A stale working goal does not spin.
        #expect(state.pill.activity == .waiting)
    }

    @Test func pending_turnsActionsOff() throws {
        var region = SessionChatGoalRegionState(goal: goal(.active))
        region.pendingAction = .pause
        let state = try #require(SessionChatGoalUiState.resolve(
            region: region,
            isChildSession: false,
            isSessionBusy: false,
            hasPermission: false,
            hasQuestion: false,
            runningShellCommand: nil,
            hasRunningSubagent: false,
            canMutate: true
        ))
        #expect(state.pending == .pause)
        #expect(!state.actionsEnabled)
        #expect(SessionChatGoalCopy.actionLabel(.pause, pending: true) == "Pausing…")
    }

    // MARK: Copy

    @Test(arguments: [
        ("Paused by the user", "You paused the goal", nil as String?),
        ("Stopped by the user", "You stopped the turn, so the goal paused", nil),
        ("The session failed: 429", "Goal paused after an error", "The session failed: 429"),
        ("Not making progress: 1 test fails", "Goal paused: no progress for 3 rounds", "Not making progress: 1 test fails"),
        ("Reached the limit of 50 rounds", "Goal paused after 50 rounds", nil),
        ("The server restarted while the goal was active", "Goal paused: the server restarted", nil),
        ("The goal reviewer gave no usable verdict", "Goal paused: the reviewer could not decide", "The goal reviewer gave no usable verdict"),
        ("Nothing ran for 30 minutes while the goal waited", "Goal paused: background work went quiet", "Nothing ran for 30 minutes while the goal waited"),
        ("Needs a decision on the sort order", "Goal paused", "Needs a decision on the sort order"),
    ])
    func copy_pauseTitleAndReasonLine(reason: String, title: String, line: String?) {
        let paused = goal(.paused, reason: reason)
        #expect(SessionChatGoalCopy.pauseTitle(paused) == title)
        #expect(SessionChatGoalCopy.pauseReason(paused) == line)
    }

    @Test func copy_progressAndStalls() {
        #expect(SessionChatGoalCopy.progress(goal(.active, rounds: 0)) == "First turn · round 0 of 50")
        #expect(SessionChatGoalCopy.progress(goal(.active, rounds: 3)) == "Round 3 of 50")
        #expect(SessionChatGoalCopy.progress(goal(.paused, rounds: 3)) == "Paused in round 3 of 50")
        #expect(SessionChatGoalCopy.progress(goal(.complete, rounds: 4)) == "Met in round 4")
        #expect(SessionChatGoalCopy.stalls(goal(.active, stalls: 0)) == nil)
        #expect(SessionChatGoalCopy.stalls(goal(.active, stalls: 2)) == "No progress for 2 rounds; pauses after 3")
        #expect(SessionChatGoalCopy.stalls(goal(.paused, stalls: 2)) == nil)
        #expect(SessionChatGoalCopy.progressFraction(goal(.active, rounds: 0)) == 0.01)
        #expect(SessionChatGoalCopy.progressFraction(goal(.active, rounds: 25)) == 0.5)
    }

    @Test func copy_composerReplyHintOnlyForTheReviewersOwnPause() {
        #expect(SessionChatGoalCopy.resumesAfterReply(resolve(goal(.paused, reason: "Needs a decision"))))
        #expect(!SessionChatGoalCopy.resumesAfterReply(resolve(goal(.paused, reason: "Paused by the user"))))
        #expect(!SessionChatGoalCopy.resumesAfterReply(resolve(goal(.active))))
        #expect(!SessionChatGoalCopy.resumesAfterReply(nil))
    }

    @Test func copy_commandRowWarnsBeforeReplacing() {
        #expect(SessionChatGoalCopy.commandSubtitle(nil) == nil)
        #expect(SessionChatGoalCopy.commandSubtitle(goal(.active)) == "Replaces your current goal")
        #expect(SessionChatGoalCopy.commandSubtitle(goal(.paused)) == "Replaces your current goal (paused)")
        #expect(SessionChatGoalCopy.commandSubtitle(goal(.complete)) == "Replaces your current goal (met)")
    }

    // MARK: Status row

    @Test func statusRow_isPlacedByUpdatedAt() throws {
        let rows = [row("u1", at: 100), row("a1", at: 200), row("u2", at: 400)]
        let placed = SessionChatGoalTimeline.withStatusRow(rows, goal: resolve(goal(.paused, updatedAt: 300)))
        #expect(placed.map(\.id) == ["u1", "a1", "goal-status:goal_1", "u2"])
        let last = SessionChatGoalTimeline.withStatusRow(rows, goal: resolve(goal(.complete, updatedAt: 999)))
        #expect(last.last?.id == "goal-status:goal_1")
        #expect(SessionChatGoalTimeline.isStatusRow(try #require(last.last)))
    }

    @Test func statusRow_rowsWithoutATimeCountAsEarlier() {
        let rows = [row("u1", at: 100), row("pending", at: nil)]
        let placed = SessionChatGoalTimeline.withStatusRow(rows, goal: resolve(goal(.paused, updatedAt: 50)))
        #expect(placed.map(\.id) == ["u1", "pending", "goal-status:goal_1"])
    }

    @Test func statusRow_onlyForPausedOrMet() {
        let rows = [row("u1", at: 100)]
        #expect(SessionChatGoalTimeline.withStatusRow(rows, goal: resolve(goal(.active))).map(\.id) == ["u1"])
        #expect(SessionChatGoalTimeline.withStatusRow(rows, goal: nil).map(\.id) == ["u1"])
    }

    @Test func renderKeys_tellTheCurrentGoalFromEarlierOnes() {
        let rows = [goalSetRow("n_old", goalID: "goal_old"), goalSetRow("n_cur", goalID: "goal_1"), row("u1", at: 1)]
        let keys = SessionChatGoalTimeline.renderKeys(rows: rows, goal: resolve(goal(.active)))
        #expect(keys["n_old"] == "ended")
        #expect(keys["n_cur"] == "current:false")
        #expect(keys["u1"] == nil)
        let cleared = SessionChatGoalTimeline.renderKeys(rows: rows, goal: nil)
        #expect(cleared["n_cur"] == "ended")
    }

    @Test func renderKeys_statusRowChangesWithItsActions() {
        let paused = goal(.paused, reason: "Paused by the user")
        let rows = SessionChatGoalTimeline.withStatusRow([row("u1", at: 1)], goal: resolve(paused))
        var region = SessionChatGoalRegionState(goal: paused)
        let idle = SessionChatGoalTimeline.renderKeys(rows: rows, goal: resolve(paused))
        region.pendingAction = .resume
        let busy = SessionChatGoalTimeline.renderKeys(rows: rows, goal: SessionChatGoalUiState.resolve(
            region: region,
            isChildSession: false,
            isSessionBusy: false,
            hasPermission: false,
            hasQuestion: false,
            runningShellCommand: nil,
            hasRunningSubagent: false,
            canMutate: true
        ))
        #expect(idle["goal-status:goal_1"] != busy["goal-status:goal_1"])
    }

    @Test func applyPacket_insertsAndRemovesTheStatusRow() {
        let base = MessageListTimelineApplyPacket.from(.empty)
        let inserted = base.addingGoalRows(keys: ["goal-status:g": "paused"], previous: [:])
        #expect(inserted.insertedRowIDs.contains("goal-status:g"))
        let removed = base.addingGoalRows(keys: [:], previous: ["goal-status:g": "paused"])
        #expect(removed.removedRowIDs.contains("goal-status:g"))
        let ended = base.addingGoalRows(keys: ["n1": "ended"], previous: ["n1": "current:false"])
        #expect(ended.changedRowIDs.contains("n1"))
        #expect(ended.frameRevision != base.addingGoalRows(keys: ["n1": "current:false"], previous: ["n1": "current:false"]).frameRevision)
    }

    // MARK: Pill slot

    @Test func contextSlot_goalFirstAndThreePills() throws {
        let pill = try #require(resolve(goal(.active), busy: true)).pill
        let branch = SessionChatBranchPillUiState(summary: OpenCodeSessionBranchSummary(branch: "feature/x"))
        let items = SessionChatPillSlots.context(goal: pill, todos: (1, 3), branch: branch)
        #expect(items == [.goal(pill), .branch(branch), .todos(completed: 1, total: 3)])
        #expect(SessionChatPillSlots.maxContextPills == 3)
        #expect(SessionChatPillSlots.context(todos: (1, 3), branch: branch) == [.branch(branch), .todos(completed: 1, total: 3)])
    }

    // MARK: Draft

    @Test func draft_hintCountAndSend() throws {
        #expect(SessionChatGoalDraftUiState.resolve(text: "/goal ", goalCommandAvailable: false) == nil)
        #expect(SessionChatGoalDraftUiState.resolve(text: "hello", goalCommandAvailable: true) == nil)
        let empty = try #require(SessionChatGoalDraftUiState.resolve(text: "/goal ", goalCommandAvailable: true))
        #expect(empty.footnote == "Describe what “done” looks like…")
        #expect(!empty.canSend)
        let short = try #require(SessionChatGoalDraftUiState.resolve(text: "/goal Ship it", goalCommandAvailable: true))
        #expect(short.footnote == nil)
        #expect(short.canSend)
        let long = try #require(SessionChatGoalDraftUiState.resolve(
            text: "/goal " + String(repeating: "a", count: 4_312),
            goalCommandAvailable: true
        ))
        #expect(long.isOverLimit)
        #expect(!long.canSend)
        #expect(long.footnote == "4,312 / 4,000 characters · shorten the objective to send")
    }

    // MARK: Helpers

    private func resolve(
        _ goal: SessionGoal?,
        busy: Bool = false,
        permission: Bool = false,
        question: Bool = false,
        shell: String? = nil,
        subagent: Bool = false,
        child: Bool = false
    ) -> SessionChatGoalUiState? {
        SessionChatGoalUiState.resolve(
            region: SessionChatGoalRegionState(goal: goal),
            isChildSession: child,
            isSessionBusy: busy,
            hasPermission: permission,
            hasQuestion: question,
            runningShellCommand: shell,
            hasRunningSubagent: subagent,
            canMutate: true
        )
    }

    private func goal(
        _ status: SessionGoalStatus,
        reason: String? = nil,
        rounds: Int = 2,
        stalls: Int? = nil,
        updatedAt: Int64 = 10
    ) -> SessionGoal {
        SessionGoal(
            id: "goal_1",
            objective: "Make the tests pass",
            status: status,
            reason: reason,
            rounds: rounds,
            maxRounds: 50,
            stalls: stalls,
            updatedAt: updatedAt
        )
    }

    private func row(_ id: String, at sentAt: Int64?) -> SessionChatRow {
        SessionChatRow(id: id, role: .user, text: id, metadataLabel: "", sentAtEpochMs: sentAt)
    }

    private func goalSetRow(_ id: String, goalID: String) -> SessionChatRow {
        SessionChatRow(
            id: id,
            role: .notice,
            text: "",
            metadataLabel: "",
            blocks: [.notice(blockID: id, notice: TimelineNotice(id: id, kind: .goalSet(goalID: goalID, description: "Goal: x")))]
        )
    }
}
