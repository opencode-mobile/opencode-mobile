import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionBranchPillLabelTests {
    private let files: [OpenCodeSessionBranchChangedFile] = [
        .init(path: "b.swift", additions: 100, deletions: 2),
        .init(path: "a.swift", additions: 20, deletions: 10),
        .init(path: "c.md", additions: 8, deletions: 2),
    ]

    @Test func changesShowIconAndCountsWithoutBranchName() {
        let summary = OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "main", files: files)

        #expect(summary.hasChanges)
        #expect(summary.changeLabel == "+128 −14")
        #expect(summary.changeLabel?.contains("feature-x") == false)
        #expect(summary.pillLayout == .iconLabel(icon: .gitBranch, label: "+128 −14"))
        #expect(summary.accessibilityLabel == "Branch feature-x, 128 added, 14 removed")
    }

    @Test func noChangesShowsIconOnly() {
        let summary = OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "main")

        #expect(!summary.hasChanges)
        #expect(summary.changeLabel == nil)
        #expect(summary.accessibilityLabel == "Branch feature-x, no changes")
        #expect(summary.pillLayout == .iconOnly(icon: .gitBranch, accessibilityLabel: "Branch feature-x, no changes"))
        #expect(summary.fileCountLabel == nil)
        #expect(summary.topFiles.isEmpty)
    }

    @Test func worktreeSessionUsesWorktreeIcon() {
        let summary = OpenCodeSessionBranchSummary(branch: "feature-x", isWorktree: true, files: files)

        #expect(summary.icon == .callSplit)
        #expect(summary.pillLayout == .iconLabel(icon: .callSplit, label: "+128 −14"))
        #expect(summary.accessibilityLabel == "Worktree branch feature-x, 128 added, 14 removed")
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", isWorktree: true).pillLayout
            == .iconOnly(icon: .callSplit, accessibilityLabel: "Worktree branch feature-x, no changes"))
    }

    @Test func zeroSidesAreLeftOut() {
        let added = OpenCodeSessionBranchSummary(branch: "x", files: [.init(path: "a", additions: 5, deletions: 0)])
        let removed = OpenCodeSessionBranchSummary(branch: "x", files: [.init(path: "a", additions: 0, deletions: 3)])
        let binary = OpenCodeSessionBranchSummary(branch: "x", files: [
            .init(path: "a.png", additions: 0, deletions: 0),
            .init(path: "b.png", additions: 0, deletions: 0),
        ])

        #expect(added.changeLabel == "+5")
        #expect(added.accessibilityLabel == "Branch x, 5 added")
        #expect(removed.changeLabel == "−3")
        #expect(removed.changeLabel?.unicodeScalars.first == Unicode.Scalar(0x2212))
        #expect(binary.changeLabel == "2 files")
        #expect(binary.accessibilityLabel == "Branch x, 2 files changed")
    }

    @Test func topFilesAreTheThreeMostChangedByLines() {
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: files + [
            .init(path: "small.txt", additions: 1, deletions: 0),
            .init(path: "big.kt", additions: 0, deletions: 50),
        ])

        #expect(summary.topFiles.map(\.path) == ["b.swift", "big.kt", "a.swift"])
        #expect(summary.fileCountLabel == "5 files changed")
    }

    @Test func topFileTiesSortByPath() {
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: [
            .init(path: "z", additions: 2, deletions: 0),
            .init(path: "y", additions: 1, deletions: 1),
        ])

        #expect(summary.topFiles.map(\.path) == ["y", "z"])
        #expect(summary.fileCountLabel == "2 files changed")
        #expect(OpenCodeSessionBranchSummary(branch: "x", files: [.init(path: "a", additions: 1, deletions: 0)])
            .fileCountLabel == "1 file changed")
    }

    @Test func baseBranchShowsOnlyWhenKnownAndDifferent() {
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "main").visibleBaseBranch == "main")
        #expect(OpenCodeSessionBranchSummary(branch: "main", baseBranch: "main").visibleBaseBranch == nil)
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: nil).visibleBaseBranch == nil)
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "").visibleBaseBranch == nil)
    }
}
