import CoreGraphics
import Testing

@testable import OpenCode_Mobile

struct MessageListTimelineRowHeightEstimateTests {
    @Test func assistantSeedIsTallerThanDefaultFloor() {
        let estimate = MessageListTimelineRowHeightEstimate.fallback(
            characterCount: 40,
            style: .assistant
        )
        #expect(estimate >= MessageListTimelineDefaults.estimatedRowHeight)
    }

    @Test func compactStaysSmall() {
        let estimate = MessageListTimelineRowHeightEstimate.fallback(
            characterCount: 2_000,
            style: .compact
        )
        #expect(abs(estimate - 64) < 0.001)
    }

    @Test func workingStatusRowUsesFixedRowHeight() {
        let row = SessionChatRow(
            id: SessionChatStatusChrome.workingRowID,
            role: .assistant,
            text: "Working",
            metadataLabel: ""
        )
        #expect(row.timelineRowHeightEstimate == WorkingTurnIndicator.rowHeight)
        #expect(row.timelineRowHeightEstimate < MessageListTimelineDefaults.estimatedRowHeight)
    }

    @Test func workingStatusRowCollapsedUsesZeroEstimate() {
        let row = SessionChatRow(
            id: SessionChatStatusChrome.workingRowID,
            role: .assistant,
            text: "Working",
            metadataLabel: "",
            isLayoutCollapsed: true
        )
        #expect(row.timelineRowHeightEstimate == 0)
    }

    @Test func longerAssistantTextRaisesEstimateWithinCap() {
        let short = MessageListTimelineRowHeightEstimate.fallback(
            characterCount: 20,
            style: .assistant
        )
        let long = MessageListTimelineRowHeightEstimate.fallback(
            characterCount: 4_000,
            style: .assistant
        )
        #expect(long > short)
        #expect(long <= 640)
    }
}

@MainActor
struct MessageListTimelineRowHeightCacheTests {
    @Test func retainsOnlyActiveIds() {
        let cache = MessageListTimelineRowHeightCache()
        cache.setHeight(120, for: "a")
        cache.setHeight(200, for: "b")
        cache.retainOnly(ids: ["a"])
        #expect(cache.height(for: "a") == 120)
        #expect(cache.height(for: "b") == nil)
    }

    @Test func ignoresNonPositiveHeights() {
        let cache = MessageListTimelineRowHeightCache()
        cache.setHeight(0, for: "a")
        cache.setHeight(-4, for: "b")
        #expect(cache.height(for: "a") == nil)
        #expect(cache.height(for: "b") == nil)
    }
}
