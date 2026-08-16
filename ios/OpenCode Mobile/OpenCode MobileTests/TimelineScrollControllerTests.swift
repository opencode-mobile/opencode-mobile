import CoreGraphics
import Testing
@testable import OpenCode_Mobile

@MainActor
private final class FakeTimelineScrollPerformer: TimelineScrollPerforming {
    struct Call: Equatable {
        let animated: Bool
    }

    private(set) var calls: [Call] = []

    func performScrollToEnd(animated: Bool) {
        calls.append(Call(animated: animated))
    }
}

@MainActor
struct TimelineScrollControllerTests {
    @Test func animatedScrollToEndPerformsOnceAndSettlesArmed() async {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 200,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.scrollToEndAnimated()
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        #expect(performer.calls == [.init(animated: true)])
        #expect(controller.isProgrammaticScrolling)

        try? await Task.sleep(for: .milliseconds(300))

        #expect(!controller.isProgrammaticScrolling)
        #expect(controller.isPinnedToEnd)
        controller.updateMessageCount(3)
        #expect(performer.calls.count == 2)
        #expect(performer.calls.last == .init(animated: false))
    }

    @Test func overlappingScrollRequestsCancelEarlierSettle() async {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.requestScrollToEnd(animated: false)
        controller.requestScrollToEnd(animated: true)

        #expect(performer.calls == [
            .init(animated: false),
            .init(animated: true),
        ])
        #expect(controller.isProgrammaticScrolling)

        try? await Task.sleep(for: .milliseconds(400))

        #expect(!controller.isProgrammaticScrolling)
        #expect(performer.calls.count == 2)
    }

    @Test func contentGrowthWhileArmedRequestsImmediateScroll() {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.notifyContentGrew()

        #expect(performer.calls == [.init(animated: false)])
    }

    @Test func contentGrowthWhileDisarmedDoesNotScroll() {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 300,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.notifyContentGrew()

        #expect(performer.calls.isEmpty)
    }

    @Test func messageAppendWhileArmedRequestsImmediateScroll() {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.updateMessageCount(3)

        #expect(performer.calls == [.init(animated: false)])
    }

    @Test func pinAndPillVisibilityTrackDistanceFromBottom() {
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )
        #expect(controller.isPinnedToEnd)
        #expect(!controller.shouldShowScrollToEnd)

        controller.updateGeometry(
            distanceFromBottom: 300,
            viewportHeight: 400,
            contentHeight: 1_000
        )
        #expect(!controller.isPinnedToEnd)
        #expect(controller.shouldShowScrollToEnd)

        controller.beginUserScrolling()
        #expect(!controller.shouldShowScrollToEnd)
        controller.endUserScrolling()
        #expect(controller.shouldShowScrollToEnd)
    }

    @Test func shortContentIsAlwaysPinned() {
        let controller = TimelineScrollController(initialMessageCount: 1)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 200
        )
        #expect(controller.isPinnedToEnd)
        #expect(!controller.shouldShowScrollToEnd)
    }

    @Test func messageAppendDuringProgrammaticScrollStillFollows() async {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 200,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.requestScrollToEnd(animated: false)
        #expect(performer.calls.count == 1)

        // Append before settle — must still request follow.
        controller.updateMessageCount(3)
        #expect(performer.calls.count == 2)
        #expect(performer.calls.last == .init(animated: false))
    }

    @Test func settleWhileUnpinnedRetriesAndKeepsFollowArmed() async {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 200,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.requestScrollToEnd(animated: false)
        #expect(performer.calls.count == 1)

        // Still short of the true bottom after estimated heights grow.
        controller.updateGeometry(
            distanceFromBottom: 120,
            viewportHeight: 400,
            contentHeight: 1_200
        )

        try? await Task.sleep(for: .milliseconds(80))

        #expect(performer.calls.count == 2)
        #expect(performer.calls.last == .init(animated: false))

        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_200
        )
        try? await Task.sleep(for: .milliseconds(80))

        controller.updateMessageCount(3)
        #expect(performer.calls.last == .init(animated: false))
        #expect(performer.calls.count == 3)
    }

    @Test func userScrollDuringProgrammaticCancelsSettleAndDisarms() async {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.requestScrollToEnd(animated: false)
        #expect(controller.isProgrammaticScrolling)
        #expect(performer.calls.count == 1)

        // User drags away while the settle window is open.
        controller.updateGeometry(
            distanceFromBottom: 300,
            viewportHeight: 400,
            contentHeight: 1_000
        )
        controller.beginUserScrolling()
        #expect(!controller.isProgrammaticScrolling)
        #expect(controller.isUserScrolling)

        try? await Task.sleep(for: .milliseconds(80))

        // Stale settle must not retry or re-arm follow.
        #expect(performer.calls.count == 1)
        controller.endUserScrolling()
        controller.updateMessageCount(3)
        controller.notifyContentGrew()
        #expect(performer.calls.count == 1)
    }

    @Test func requestScrollToEndIgnoredWhileUserScrolling() {
        let performer = FakeTimelineScrollPerformer()
        let controller = TimelineScrollController(initialMessageCount: 2)
        controller.attach(performer)
        controller.updateGeometry(
            distanceFromBottom: 300,
            viewportHeight: 400,
            contentHeight: 1_000
        )

        controller.beginUserScrolling()
        controller.requestScrollToEnd(animated: false)
        controller.scrollToEndAnimated()

        #expect(performer.calls.isEmpty)
        #expect(!controller.isProgrammaticScrolling)
    }

    @Test func collapsedViewportDoesNotRePinWhenUserWasUnpinned() {
        let controller = TimelineScrollController(initialMessageCount: 8)
        controller.updateGeometry(
            distanceFromBottom: 400,
            viewportHeight: 800,
            contentHeight: 2_000
        )
        controller.beginUserScrolling()
        controller.endUserScrolling()
        #expect(!controller.isPinnedToEnd)

        controller.updateGeometry(
            distanceFromBottom: 0,
            viewportHeight: 0,
            contentHeight: 2_000
        )

        #expect(!controller.isPinnedToEnd)
        controller.notifyContentGrew()
        #expect(!controller.isPinnedToEnd)
    }
}
