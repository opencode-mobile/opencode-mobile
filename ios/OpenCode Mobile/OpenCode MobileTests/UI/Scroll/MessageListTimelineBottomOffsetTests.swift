import CoreGraphics
import Testing

@testable import OpenCode_Mobile

struct MessageListTimelineBottomOffsetTests {
    @Test func includesAdjustedBottomInsetInScrollableExtent() {
        let y = MessageListTimelineBottomOffset.y(
            contentHeight: 2_000,
            viewportHeight: 800,
            adjustedTopInset: 100,
            adjustedBottomInset: 34
        )
        #expect(abs(y - 1_234) < 0.001)
    }

    @Test func clampsToNegativeTopInsetWhenContentFits() {
        let y = MessageListTimelineBottomOffset.y(
            contentHeight: 400,
            viewportHeight: 800,
            adjustedTopInset: 120,
            adjustedBottomInset: 34
        )
        #expect(abs(y - (-120)) < 0.001)
    }

    @Test func sectionStyleBottomPaddingSurvivesAsPartOfContentHeight() {
        // contentHeight already includes section bottom inset; scrollToItem(.bottom)
        // on the last cell would miss that padding — absolute offset must not.
        let withoutPadding = MessageListTimelineBottomOffset.y(
            contentHeight: 1_000,
            viewportHeight: 800,
            adjustedTopInset: 0,
            adjustedBottomInset: 0
        )
        let withPadding = MessageListTimelineBottomOffset.y(
            contentHeight: 1_000 + MessageListTimelineDefaults.bottomContentPadding,
            viewportHeight: 800,
            adjustedTopInset: 0,
            adjustedBottomInset: 0
        )
        #expect(abs((withPadding - withoutPadding) - MessageListTimelineDefaults.bottomContentPadding) < 0.001)
    }
}
