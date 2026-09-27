import Foundation
import Testing
@testable import OpenCode_Mobile

struct SessionBranchPillLabelTests {
    @Test func cleanBranchOmitsCounts() {
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: 0) == nil)
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: 0) == nil)
        #expect(OpenCodeSessionBranchPill.accessibilityLabel(branch: "main", additions: 0, deletions: 0) == "Branch main")
    }

    @Test func countsAppearOnlyWhenPositive() {
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: 12) == "+12")
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: 3) == "−3")
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: 3)?.unicodeScalars.first == Unicode.Scalar(0x2212))
        #expect(OpenCodeSessionBranchPill.additionLabel(additions: 0) == nil)
        #expect(OpenCodeSessionBranchPill.deletionLabel(deletions: 0) == nil)
        #expect(
            OpenCodeSessionBranchPill.accessibilityLabel(branch: "fix/probe", additions: 1, deletions: 0)
                == "Branch fix/probe, 1 addition"
        )
        #expect(
            OpenCodeSessionBranchPill.accessibilityLabel(branch: "fix/probe", additions: 0, deletions: 1)
                == "Branch fix/probe, 1 deletion"
        )
        #expect(
            OpenCodeSessionBranchPill.accessibilityLabel(branch: "fix/probe", additions: 12, deletions: 3)
                == "Branch fix/probe, 12 additions, 3 deletions"
        )
    }

    @Test func accessibilityUsesFullBranch() {
        let branch = SessionBranchPillPreviewCase.longBranch
        #expect(
            OpenCodeSessionBranchPill.accessibilityLabel(branch: branch, additions: 4, deletions: 1)
                == "Branch \(branch), 4 additions, 1 deletion"
        )
    }
}
