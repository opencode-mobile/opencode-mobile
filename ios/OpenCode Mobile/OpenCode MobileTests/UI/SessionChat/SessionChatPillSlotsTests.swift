import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatPillSlotsTests {
    private let now = Date(timeIntervalSinceReferenceDate: 1_000)

    // MARK: Activity priority and overflow

    @Test func priorityOrderPicksTheMostUrgentAndStacksTheRest() {
        let finished = SessionChatActivityItem.finished(id: "a", label: "Done", finishedAt: now)
        let slot = SessionChatPillSlots.activity(
            items: [
                finished,
                .queued(count: 3),
                .sideQuestionsRunning(count: 1),
                .sideQuestionsReady(count: 1),
                .requestFailed(rowID: "m1", blockID: "m1:failure"),
                .reviewPermission,
            ],
            keyboardVisible: false,
            now: now
        )

        #expect(slot.primary == .reviewPermission)
        #expect(slot.overflow == [
            .requestFailed(rowID: "m1", blockID: "m1:failure"),
            .sideQuestionsReady(count: 1),
            .sideQuestionsRunning(count: 1),
            .queued(count: 3),
            finished,
        ])
        #expect(slot.showsOverflowButton)
        #expect(!slot.primaryCompact)
    }

    @Test func priorityRanksMatchTheSpec() {
        let ranks: [SessionChatActivityPriority] = [.needsInput, .error, .answerReady, .running, .queued, .finished]
        #expect(ranks == ranks.sorted())
        #expect(SessionChatActivityItem.answerQuestion.priority == .needsInput)
        #expect(SessionChatActivityItem.sideQuestionsFailed(count: 1).priority == .error)
        #expect(SessionChatActivityItem.syncing(label: "Syncing").priority == .running)
    }

    // MARK: Background work (subagents)

    private let runningSubagents = SessionChatBackgroundWorkPill(
        label: "3 subagents", needsInput: false, accessibilityLabel: "3 subagents running"
    )
    private let waitingSubagent = SessionChatBackgroundWorkPill(
        label: "general needs input · 2 running",
        needsInput: true,
        accessibilityLabel: "general needs input, 2 subagents running"
    )

    @Test func runningSubagentsRankAsRunningWork() {
        let slot = SessionChatPillSlots.activity(
            items: [.queued(count: 1), .subagents(runningSubagents), .sideQuestionsReady(count: 1)],
            keyboardVisible: false,
            now: now
        )

        #expect(SessionChatActivityItem.subagents(runningSubagents).priority == .running)
        #expect(slot.primary == .sideQuestionsReady(count: 1))
        #expect(slot.overflow == [.subagents(runningSubagents), .queued(count: 1)])
    }

    @Test func waitingSubagentNeedsInputAndStaysFullWithTheKeyboardUp() {
        let slot = SessionChatPillSlots.activity(
            items: [.sideQuestionsFailed(count: 1), .subagents(waitingSubagent)],
            keyboardVisible: true,
            now: now
        )

        #expect(slot.primary == .subagents(waitingSubagent))
        #expect(!slot.primaryCompact)
    }

    @Test func subagentsPillUsesTheCounterWithThePulsingDotWhileWaiting() {
        #expect(SessionChatActivitySlotView.layout(.subagents(runningSubagents)) == .counter(label: "3 subagents", waiting: false))
        #expect(SessionChatActivitySlotView.state(.subagents(runningSubagents)) == .live)
        #expect(
            SessionChatActivitySlotView.layout(.subagents(waitingSubagent))
                == .counter(label: "general needs input · 2 running", waiting: true)
        )
        #expect(SessionChatActivitySlotView.state(.subagents(waitingSubagent)) == .needsAttention)
        #expect(
            OpenCodeFloatingPillStyle.leading(
                layout: SessionChatActivitySlotView.layout(.subagents(waitingSubagent)),
                state: SessionChatActivitySlotView.state(.subagents(waitingSubagent))
            ) == .pulsingDot
        )
        #expect(SessionChatActivitySlotView.isTappable(.subagents(runningSubagents)))
        #expect(!SessionChatActivitySlotView.isClosable(.subagents(runningSubagents)))
        #expect(
            SessionChatActivitySlotView.accessibilityLabel(.subagents(waitingSubagent))
                == "general needs input, 2 subagents running. Open subagents"
        )
    }

    @Test func equalPrioritiesKeepTheCallersOrder() {
        let slot = SessionChatPillSlots.activity(
            items: [.sideQuestionsRunning(count: 2), .syncing(label: "Syncing")],
            keyboardVisible: false,
            now: now
        )

        #expect(slot.primary == .sideQuestionsRunning(count: 2))
        #expect(slot.overflow == [.syncing(label: "Syncing")])
    }

    @Test func singleItemHasNoOverflowButton() {
        let slot = SessionChatPillSlots.activity(items: [.queued(count: 1)], keyboardVisible: false, now: now)

        #expect(slot.primary == .queued(count: 1))
        #expect(slot.overflow.isEmpty)
        #expect(!slot.showsOverflowButton)
    }

    @Test func noItemsIsEmpty() {
        #expect(SessionChatPillSlots.activity(items: [], keyboardVisible: true, now: now) == .empty)
    }

    // MARK: Keyboard

    @Test func keyboardCompactsThePillAndHidesOverflow() {
        let slot = SessionChatPillSlots.activity(
            items: [.queued(count: 2), .sideQuestionsReady(count: 1)],
            keyboardVisible: true,
            now: now
        )

        #expect(slot.primary == .sideQuestionsReady(count: 1))
        #expect(slot.primaryCompact)
        #expect(slot.overflow.isEmpty)
        #expect(!slot.showsOverflowButton)
    }

    @Test func needsInputKeepsItsLabelWithTheKeyboardUp() {
        let slot = SessionChatPillSlots.activity(
            items: [.queued(count: 2), .answerQuestion],
            keyboardVisible: true,
            now: now
        )

        #expect(slot.primary == .answerQuestion)
        #expect(!slot.primaryCompact)
        #expect(!slot.showsOverflowButton)
    }

    @Test func keyboardHidesContextAndNavigation() {
        let branch = SessionChatBranchPillUiState(summary: OpenCodeSessionBranchSummary(
            branch: "main",
            files: [OpenCodeSessionBranchChangedFile(path: "a.kt", additions: 1, deletions: 2)]
        ))
        #expect(!SessionChatPillSlots.showsContext(keyboardVisible: true))
        #expect(SessionChatPillSlots.showsContext(keyboardVisible: false))
        // The items stay, so the top row keeps its measured inset while hidden.
        #expect(SessionChatPillSlots.context(todos: (1, 3), branch: branch).count == 2)
        #expect(SessionChatPillSlots.navigation(awayFromEnd: true, newCount: 2, keyboardVisible: true) == nil)
    }

    // MARK: Finished fade

    @Test func finishedShowsAloneUntilItFades() {
        let finished = SessionChatActivityItem.finished(id: "a", label: "Done", finishedAt: now)

        let fresh = SessionChatPillSlots.activity(items: [finished], keyboardVisible: false, now: now.addingTimeInterval(2.9))
        let faded = SessionChatPillSlots.activity(items: [finished], keyboardVisible: false, now: now.addingTimeInterval(3))

        #expect(fresh.primary == finished)
        #expect(faded == .empty)
    }

    @Test func finishedOnlyShowsInTheStackBesideOtherWork() {
        let finished = SessionChatActivityItem.finished(id: "a", label: "Done", finishedAt: now)
        let slot = SessionChatPillSlots.activity(
            items: [finished, .sideQuestionsRunning(count: 1)],
            keyboardVisible: false,
            now: now.addingTimeInterval(1)
        )

        #expect(slot.primary == .sideQuestionsRunning(count: 1))
        #expect(slot.overflow == [finished])
    }

    @Test func nextFadeIsTheEarliestUnfadedFinish() {
        let items: [SessionChatActivityItem] = [
            .finished(id: "old", label: "Done", finishedAt: now.addingTimeInterval(-5)),
            .finished(id: "a", label: "Done", finishedAt: now.addingTimeInterval(-1)),
            .finished(id: "b", label: "Done", finishedAt: now),
            .queued(count: 1),
        ]

        #expect(SessionChatPillSlots.nextFade(items: items, now: now) == now.addingTimeInterval(2))
        #expect(SessionChatPillSlots.nextFade(items: [.queued(count: 1)], now: now) == nil)
    }

    // MARK: Context

    @Test func contextShowsBranchInTheCornerThenTodosAtMostTwo() {
        let branch = SessionChatBranchPillUiState(summary: OpenCodeSessionBranchSummary(
            branch: "main",
            files: [OpenCodeSessionBranchChangedFile(path: "a.kt", additions: 1, deletions: 2)]
        ))

        #expect(SessionChatPillSlots.context(todos: (2, 5), branch: branch) == [
            .branch(branch),
            .todos(completed: 2, total: 5),
        ])
        #expect(SessionChatPillSlots.context(todos: (0, 0), branch: branch) == [.branch(branch)])
        #expect(SessionChatPillSlots.context(todos: nil, branch: nil).isEmpty)
        #expect(SessionChatPillSlots.maxContextPills == 2)
    }

    // MARK: Navigation

    @Test func navigationIsEmptyAtTheEnd() {
        #expect(SessionChatPillSlots.navigation(awayFromEnd: false, newCount: 3, keyboardVisible: false) == nil)
        #expect(SessionChatPillSlots.navigation(awayFromEnd: true, newCount: 0, keyboardVisible: false) == .scrollToEnd(newCount: 0))
        #expect(SessionChatPillSlots.navigation(awayFromEnd: true, newCount: 3, keyboardVisible: false) == .scrollToEnd(newCount: 3))
    }

    @Test func newMessagesCountOnlyRowsAfterTheBaseline() {
        #expect(SessionChatPillSlots.newMessageCount(rowIDs: ["a", "b", "c", "d"], baselineLastID: "b") == 2)
        // Older history prepended above does not count.
        #expect(SessionChatPillSlots.newMessageCount(rowIDs: ["x", "a", "b"], baselineLastID: "b") == 0)
        #expect(SessionChatPillSlots.newMessageCount(rowIDs: ["a"], baselineLastID: nil) == 0)
        #expect(SessionChatPillSlots.newMessageCount(rowIDs: ["a"], baselineLastID: "gone") == 0)
    }

    // MARK: Request failed

    @Test func latestFailureIsTheLastTurnsFailure() {
        let rows = [user("u1"), assistant("a1", failure: failure("a1:failure"))]

        let found = SessionChatPillSlots.latestFailure(in: rows, isRetrying: false)
        #expect(found?.rowID == "a1")
        #expect(found?.blockID == "a1:failure")
    }

    @Test func noFailurePillAfterANewPromptWhileRetryingOrForNeutralMarks() {
        let failed = assistant("a1", failure: failure("a1:failure"))

        #expect(SessionChatPillSlots.latestFailure(in: [failed, user("u2")], isRetrying: false) == nil)
        #expect(SessionChatPillSlots.latestFailure(in: [failed], isRetrying: true) == nil)
        #expect(SessionChatPillSlots.latestFailure(
            in: [assistant("a2", failure: failure("a2:failure", neutral: true))],
            isRetrying: false
        ) == nil)
    }

    private func user(_ id: String) -> SessionChatRow {
        SessionChatRow(id: id, role: .user, text: "Hi", metadataLabel: "")
    }

    private func assistant(_ id: String, failure: SessionChatTimelineBlock) -> SessionChatRow {
        SessionChatRow(id: id, role: .assistant, text: "", metadataLabel: "", blocks: [failure])
    }

    private func failure(_ blockID: String, neutral: Bool = false) -> SessionChatTimelineBlock {
        .failureLine(
            blockID: blockID,
            text: "HTTP 500 · Server error",
            providerBody: nil,
            isNeutral: neutral,
            action: nil,
            actionLabel: nil,
            coveredByLiveRetry: false
        )
    }
}
