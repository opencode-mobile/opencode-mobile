import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionChatBranchPillTests {
    @Test func knownBranchShowsPillWithSummedCounts() {
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

        #expect(pill == SessionChatBranchPillUiState(branch: "fix/probe", additions: 42, deletions: 26))
        #expect(SessionBranchPillVisibility.shouldShow(pill: pill, hideFloatStack: false))
    }

    @Test func additionsOnlyAndDeletionsOnlyKeepZeroSide() {
        let additionsOnly = SessionChatBranchPillUiState.resolved(from: region(branch: "fix/probe", files: [
            VcsFileStatus(path: "a.kt", status: .added, additions: 5, deletions: 0),
        ]))
        let deletionsOnly = SessionChatBranchPillUiState.resolved(from: region(branch: "fix/probe", files: [
            VcsFileStatus(path: "a.kt", status: .deleted, additions: 0, deletions: 2),
        ]))

        #expect(additionsOnly == SessionChatBranchPillUiState(branch: "fix/probe", additions: 5, deletions: 0))
        #expect(deletionsOnly == SessionChatBranchPillUiState(branch: "fix/probe", additions: 0, deletions: 2))
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: additionsOnly?.additions ?? -1) == "+5")
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: additionsOnly?.deletions ?? -1) == nil)
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: deletionsOnly?.additions ?? -1) == nil)
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: deletionsOnly?.deletions ?? -1) == "−2")
    }

    @Test func cleanBranchShowsNameWithoutCounts() {
        let pill = SessionChatBranchPillUiState.resolved(from: region(branch: "main", files: []))

        #expect(pill == SessionChatBranchPillUiState(branch: "main", additions: 0, deletions: 0))
        #expect(SessionBranchPillVisibility.shouldShow(pill: pill, hideFloatStack: false))
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: 0) == nil)
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: 0) == nil)
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
        let pill = SessionChatBranchPillUiState(branch: "main", additions: 1, deletions: 0)
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
