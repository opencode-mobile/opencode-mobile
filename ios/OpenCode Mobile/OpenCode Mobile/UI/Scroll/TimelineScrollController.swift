import CoreGraphics
import Foundation

@MainActor
protocol TimelineScrollPerforming: AnyObject {
    func performScrollToEnd(animated: Bool)
}

@Observable
@MainActor
final class TimelineScrollController {
    private(set) var isPinnedToEnd = true
    private(set) var isUserScrolling = false
    private(set) var isProgrammaticScrolling = false
    private(set) var shouldShowScrollToEnd = false

    var autoFollowEnabled = true

    private var autoFollowState: MessageListAutoFollowState
    private weak var performer: TimelineScrollPerforming?
    private var scrollGeneration = 0
    private var programmaticPinRetryCount = 0
    private var distanceFromBottom = CGFloat.greatestFiniteMagnitude
    private var viewportHeight: CGFloat = 0
    private var contentHeight: CGFloat = 0
    private var messageCount: Int

    init(initialMessageCount: Int = 0) {
        messageCount = initialMessageCount
        autoFollowState = MessageListAutoFollowState(initialMessageCount: initialMessageCount)
    }

    func attach(_ performer: TimelineScrollPerforming) {
        self.performer = performer
    }

    func reset(messageCount: Int) {
        self.messageCount = messageCount
        autoFollowState = MessageListAutoFollowState(initialMessageCount: messageCount)
        refreshDerivedState()
        autoFollowState.updatePinnedState(
            isPinnedToEnd: isPinnedToEnd,
            messageCount: messageCount
        )
    }

    func updateGeometry(
        distanceFromBottom: CGFloat,
        viewportHeight: CGFloat,
        contentHeight: CGFloat
    ) {
        // Off-screen / collapsed viewports (NavigationStack cover) report a
        // bogus pin-to-end. Keep the last real geometry instead of re-arming.
        guard viewportHeight > 1 else { return }

        self.distanceFromBottom = distanceFromBottom
        self.viewportHeight = viewportHeight
        self.contentHeight = contentHeight
        refreshDerivedState()

        if !isUserScrolling, !isProgrammaticScrolling {
            autoFollowState.updatePinnedState(
                isPinnedToEnd: isPinnedToEnd,
                messageCount: messageCount
            )
        }
    }

    func updateMessageCount(_ messageCount: Int) {
        let shouldFollow = autoFollowState.onMessageCountChanged(
            messageCount: messageCount,
            autoFollowEnabled: autoFollowEnabled
        )
        self.messageCount = messageCount
        refreshDerivedState()
        guard shouldFollow else { return }
        requestScrollToEnd(animated: false)
    }

    func notifyContentGrew() {
        guard autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled: autoFollowEnabled) else {
            return
        }
        requestScrollToEnd(animated: false)
    }

    func scrollToEndAnimated() {
        requestScrollToEnd(animated: true)
    }

    func requestScrollToEnd(animated: Bool) {
        guard messageCount > 0 || contentHeight > 0 else { return }
        // Never fight an active user pan/deceleration; follow stays gated by
        // shouldFollowNextAppend until the gesture ends.
        guard !isUserScrolling else { return }

        scrollGeneration += 1
        let generation = scrollGeneration
        isProgrammaticScrolling = true
        // Arm immediately so appends/layout growth during the settle window still follow.
        if autoFollowEnabled {
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: true)
        }
        refreshDerivedState()
        performer?.performScrollToEnd(animated: animated)

        Task { @MainActor in
            let settleMilliseconds = animated
                ? MessageListTimelineDefaults.programmaticAnimatedSettleMilliseconds
                : MessageListTimelineDefaults.programmaticImmediateSettleMilliseconds
            try? await Task.sleep(for: .milliseconds(settleMilliseconds))
            guard generation == scrollGeneration else { return }

            isProgrammaticScrolling = false
            refreshDerivedState()

            let followStillArmed = autoFollowState.shouldEnsureLayoutEnd(
                autoFollowEnabled: autoFollowEnabled
            )

            // Estimated UICollectionView cell heights often grow after the first
            // scroll-to-end, leaving us short of the true bottom. Retry only while
            // follow is still armed — a user scroll away must cancel retries.
            if followStillArmed,
               !isPinnedToEnd,
               programmaticPinRetryCount < MessageListTimelineDefaults.programmaticPinRetryLimit
            {
                programmaticPinRetryCount += 1
                autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: true)
                requestScrollToEnd(animated: false)
                return
            }

            programmaticPinRetryCount = 0
            // Keep armed after an intentional pin miss only if the user did not
            // disarm during settle. Never re-arm from autoFollowEnabled alone.
            autoFollowState.markProgrammaticScrollSettled(
                isPinnedToEnd: isPinnedToEnd || followStillArmed
            )
        }
    }

    func beginUserScrolling() {
        if isProgrammaticScrolling {
            // Cancel in-flight settle/pin retries so follow corrections stop
            // fighting the gesture immediately.
            scrollGeneration += 1
            isProgrammaticScrolling = false
            programmaticPinRetryCount = 0
        }
        isUserScrolling = true
        refreshDerivedState()
        // User drag is the only path that should disarm follow from an unpinned offset.
        autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: isPinnedToEnd)
    }

    func endUserScrolling() {
        isUserScrolling = false
        refreshDerivedState()
        autoFollowState.updatePinnedState(
            isPinnedToEnd: isPinnedToEnd,
            messageCount: messageCount
        )
    }

    private func refreshDerivedState() {
        let scrollable = contentHeight > viewportHeight + MessageListTimelineDefaults.pinnedTolerance
        isPinnedToEnd = !scrollable || distanceFromBottom <= MessageListTimelineDefaults.autoFollowTolerance

        if isProgrammaticScrolling || !scrollable || messageCount == 0 {
            shouldShowScrollToEnd = false
            return
        }

        shouldShowScrollToEnd = ScrollToEndVisibility.shouldShow(
            scrollDistanceFromBottom: distanceFromBottom,
            viewportHeight: viewportHeight,
            isUserScrolling: isUserScrolling
        )
    }
}
