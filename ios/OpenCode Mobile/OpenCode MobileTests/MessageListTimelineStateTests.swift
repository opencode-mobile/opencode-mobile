import CoreGraphics
import Testing
@testable import OpenCode_Mobile

@MainActor
struct MessageListTimelineStateTests {
    @Test func scrollToEndPillStaysHiddenForProgrammaticAnimation() async throws {
        let timeline = MessageListTimelineState(bottomAnchorID: "bottom")
        timeline.contentHeight = 1_000
        timeline.viewportHeight = 400
        timeline.scrollDistanceFromBottom = 300
        timeline.scrollPositionID = "first"

        #expect(timeline.shouldShowScrollToEnd(messageCount: 2))

        timeline.animateToEnd(messageCount: 2)

        #expect(timeline.isProgrammaticScrolling)
        #expect(!timeline.shouldShowScrollToEnd(messageCount: 2))

        try await Task.sleep(for: .milliseconds(50))

        #expect(timeline.isProgrammaticScrolling)
        #expect(!timeline.shouldShowScrollToEnd(messageCount: 2))

        timeline.scrollDistanceFromBottom = 0
        try await Task.sleep(for: .milliseconds(200))

        #expect(!timeline.isProgrammaticScrolling)
        #expect(!timeline.shouldShowScrollToEnd(messageCount: 2))
    }
}
