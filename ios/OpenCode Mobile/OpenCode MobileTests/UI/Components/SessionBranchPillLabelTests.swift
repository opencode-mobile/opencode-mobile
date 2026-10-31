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
        #expect(binary.accessibilityLabel == "Branch x, 2 uncommitted files")
    }

    @Test func topFilesAreTheThreeMostChangedByLines() {
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: files + [
            .init(path: "small.txt", additions: 1, deletions: 0),
            .init(path: "big.kt", additions: 0, deletions: 50),
        ])

        #expect(summary.topFiles.map(\.path) == ["b.swift", "big.kt", "a.swift"])
        #expect(summary.fileCountLabel == "5 uncommitted files")
    }

    @Test func topFileTiesSortByPath() {
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: [
            .init(path: "z", additions: 2, deletions: 0),
            .init(path: "y", additions: 1, deletions: 1),
        ])

        #expect(summary.topFiles.map(\.path) == ["y", "z"])
        #expect(summary.fileCountLabel == "2 uncommitted files")
        #expect(OpenCodeSessionBranchSummary(branch: "x", files: [.init(path: "a", additions: 1, deletions: 0)])
            .fileCountLabel == "1 uncommitted file")
    }

    @Test func baseBranchShowsOnlyWhenKnownAndDifferent() {
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "main").visibleBaseBranch == "main")
        #expect(OpenCodeSessionBranchSummary(branch: "main", baseBranch: "main").visibleBaseBranch == nil)
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: nil).visibleBaseBranch == nil)
        #expect(OpenCodeSessionBranchSummary(branch: "feature-x", baseBranch: "").visibleBaseBranch == nil)
    }

    @Test func popoverHidesZeroSidesAndNeverSpeaksZero() {
        let deletionOnly = OpenCodeSessionBranchChangedFile(path: "src/old.ts", additions: 0, deletions: 42)
        let binary = OpenCodeSessionBranchChangedFile(path: "assets/logo.png", additions: 0, deletions: 0)
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: [deletionOnly, binary])

        #expect(deletionOnly.visibleAdditions == nil)
        #expect(deletionOnly.visibleDeletions == 42)
        #expect(deletionOnly.accessibilityLabel == "src/old.ts, 42 removed")
        #expect(binary.visibleAdditions == nil)
        #expect(binary.visibleDeletions == nil)
        #expect(binary.accessibilityLabel == "assets/logo.png")
        #expect(summary.visibleAdditions == nil)
        #expect(summary.visibleDeletions == 42)
        #expect(summary.fileCountAccessibilityLabel == "2 uncommitted files, 42 removed")
        #expect(summary.topFiles.map(\.path) == ["src/old.ts", "assets/logo.png"])
        for label in [deletionOnly.accessibilityLabel, binary.accessibilityLabel, summary.fileCountAccessibilityLabel ?? "", summary.accessibilityLabel] {
            #expect(!label.contains("0 added"))
            #expect(!label.contains("0 removed"))
        }
    }

    @Test func popoverTotalsSpeakBothSidesWhenPresent() {
        let summary = OpenCodeSessionBranchSummary(branch: "x", files: files)
        #expect(summary.visibleAdditions == 128)
        #expect(summary.visibleDeletions == 14)
        #expect(summary.fileCountAccessibilityLabel == "3 uncommitted files, 128 added, 14 removed")
        #expect(files[0].accessibilityLabel == "b.swift, 100 added, 2 removed")
    }
}
