import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatTopPillTests {
    private let branch = SessionChatBranchPillUiState(branch: "main", additions: 3, deletions: 1)

    @Test func syncingWinsOverTodosAndBranch() {
        let pill = SessionChatTopPill.resolve(
            syncingLabel: SessionMessageSyncChrome.statusLabel,
            todosVisible: true,
            todoTotalCount: 2,
            branchPill: branch,
            hideFloatStack: false
        )

        #expect(pill == .syncing(label: SessionMessageSyncChrome.statusLabel))
    }

    @Test func todosWinOverBranch() {
        let pill = SessionChatTopPill.resolve(
            syncingLabel: nil,
            todosVisible: true,
            todoTotalCount: 2,
            branchPill: branch,
            hideFloatStack: false
        )

        #expect(pill == .todos)
    }

    @Test func branchShowsWhenNothingElseDoes() {
        let hiddenTodos = SessionChatTopPill.resolve(
            syncingLabel: nil,
            todosVisible: false,
            todoTotalCount: 2,
            branchPill: branch,
            hideFloatStack: false
        )
        let emptyTodos = SessionChatTopPill.resolve(
            syncingLabel: nil,
            todosVisible: true,
            todoTotalCount: 0,
            branchPill: branch,
            hideFloatStack: false
        )

        #expect(hiddenTodos == .branch(branch))
        #expect(emptyTodos == .branch(branch))
    }

    @Test func nothingToShowResolvesToNil() {
        let pill = SessionChatTopPill.resolve(
            syncingLabel: nil,
            todosVisible: false,
            todoTotalCount: 0,
            branchPill: nil,
            hideFloatStack: false
        )

        #expect(pill == nil)
    }

    @Test func todosSheetHidesTodosAndBranchButKeepsSyncing() {
        let quiet = SessionChatTopPill.resolve(
            syncingLabel: nil,
            todosVisible: true,
            todoTotalCount: 2,
            branchPill: branch,
            hideFloatStack: true
        )
        let syncing = SessionChatTopPill.resolve(
            syncingLabel: SessionMessageSyncChrome.statusLabel,
            todosVisible: true,
            todoTotalCount: 2,
            branchPill: branch,
            hideFloatStack: true
        )

        #expect(quiet == nil)
        #expect(syncing == .syncing(label: SessionMessageSyncChrome.statusLabel))
    }
}
