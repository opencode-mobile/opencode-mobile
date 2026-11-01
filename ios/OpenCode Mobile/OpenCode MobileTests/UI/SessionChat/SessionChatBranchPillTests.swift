import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatBranchPillTests {
    @Test func knownBranchShowsPillWithSummedCountsAndBase() {
        let pill = SessionChatBranchPillUiState.resolved(from: SessionChatRepoStripRegionState(
            isAvailable: true,
            branch: "fix/probe",
            defaultBranch: "main",
            files: [
                VcsFileStatus(path: "a.kt", status: .modified, additions: 42, deletions: 7),
                VcsFileStatus(path: "b.md", status: .deleted, additions: 0, deletions: 19),
            ],
            isExpanded: true
        ))

        #expect(pill?.branch == "fix/probe")
        #expect(pill?.additions == 42)
        #expect(pill?.deletions == 26)
        #expect(pill?.summary.visibleBaseBranch == "main")
        #expect(pill?.summary.isWorktree == false)
        #expect(pill?.summary.files == [
            .init(path: "a.kt", additions: 42, deletions: 7),
            .init(path: "b.md", additions: 0, deletions: 19),
        ])
        #expect(SessionBranchPillVisibility.shouldShow(pill: pill, hideFloatStack: false))
    }

    @Test func worktreeCheckoutMarksTheSummary() {
        let pill = SessionChatBranchPillUiState.resolved(from: region(branch: "fix/probe", files: []), isWorktree: true)

        #expect(pill?.summary.isWorktree == true)
        #expect(pill?.summary.icon == .callSplit)
    }

    @Test func cleanBranchShowsIconOnlyPill() {
        let pill = SessionChatBranchPillUiState.resolved(from: region(branch: "main", files: []))

        #expect(pill?.additions == 0)
        #expect(pill?.deletions == 0)
        #expect(pill?.summary.changeLabel == nil)
        #expect(SessionBranchPillVisibility.shouldShow(pill: pill, hideFloatStack: false))
    }

    @Test func unavailableOrEmptyBranchHidesPill() {
        #expect(SessionChatBranchPillUiState.resolved(from: SessionChatRepoStripRegionState()) == nil)
        #expect(SessionChatBranchPillUiState.resolved(from: region(branch: "", files: [
            VcsFileStatus(path: "a.kt", status: .modified, additions: 1, deletions: 1),
        ], isAvailable: true)) == nil)
        #expect(SessionChatBranchPillUiState.resolved(from: SessionChatRepoStripRegionState(
            isAvailable: false,
            branch: "fix/probe"
        )) == nil)
        #expect(!SessionBranchPillVisibility.shouldShow(pill: nil, hideFloatStack: false))
    }

    @Test func todosSheetHidesKnownBranch() {
        let pill = SessionChatBranchPillUiState.resolved(from: region(branch: "main", files: []))
        #expect(!SessionBranchPillVisibility.shouldShow(pill: pill, hideFloatStack: true))
    }

    private func region(
        branch: String,
        files: [VcsFileStatus],
        isAvailable: Bool = true
    ) -> SessionChatRepoStripRegionState {
        SessionChatRepoStripRegionState(
            isAvailable: isAvailable,
            branch: branch,
            files: files
        )
    }
}
