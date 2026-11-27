import Foundation
import Testing
@testable import OpenCode_Mobile

/// Counts read in the singular for 1 and keep their plural English otherwise (#886).
struct CountGrammarTextTests {
    @Test func activityCardAgentCount() {
        func label(_ count: Int) -> String {
            String(localized: LocalizedStringResource("component.activity_card.agent_count", defaultValue: "\(count) agents"))
        }
        #expect(label(1) == "1 agent")
        #expect(label(3) == "3 agents")
    }

    @Test func namedToolCountKeepsTheServerName() {
        let count = 3
        let name = "mcp_lookup"
        #expect(String(localized: "tool.count.named", defaultValue: "\(count) \(name)") == "3 mcp_lookup")
    }

    @Test func mcpHeadlineFollowsTheConfiguredCount() {
        #expect(SessionChatMcpServersSheetContent.headline(connected: 1, total: 1).resolved == "1 of 1 connected")
        #expect(SessionChatMcpServersSheetContent.headline(connected: 2, total: 3).resolved == "2 of 3 connected")
    }

    @Test func unifiedDiffSummaryCountsAdditionsAndDeletions() {
        func summary(_ additions: Int, _ deletions: Int) -> String {
            let file = FileDiff(path: "src/a.swift", patch: nil, additions: additions, deletions: deletions, status: .modified)
            return String(localized: OpenCodeUnifiedDiff.fileSummary(file))
        }
        #expect(summary(1, 1) == "Modified file src/a.swift, 1 addition, 1 deletion")
        #expect(summary(3, 0) == "Modified file src/a.swift, 3 additions, 0 deletions")
    }

    @Test func reviewChangesPillSingularAndPlural() {
        #expect(
            OpenCodeReviewChangesPill.accessibilityLabel(changedFileCount: 1, additions: 1, deletions: 1)
                == "Review changes, 1 file, 1 addition, 1 deletion"
        )
        #expect(
            OpenCodeReviewChangesPill.accessibilityLabel(changedFileCount: 5, additions: 85, deletions: 34)
                == "Review changes, 5 files, 85 additions, 34 deletions"
        )
        #expect(OpenCodeReviewChangesPill.accessibilityLabel(changedFileCount: 4, additions: 0, deletions: 0) == "Review changes, 4 files")
        #expect(OpenCodeReviewChangesPill.visibleSummary(changedFileCount: 1, additions: 1, deletions: 1) == "1 file  +1  \u{2212}1")
    }
}
