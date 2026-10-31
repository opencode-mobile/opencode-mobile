import CoreGraphics
import Foundation

@MainActor
protocol TimelineScrollPerforming: AnyObject {
    func performScrollToEnd(animated: Bool)
    /// Scroll a row into view and flash it.
    func performRevealItem(id: String)
}

extension TimelineScrollPerforming {
    func performRevealItem(id: String) {}
}

@Observable
@MainActor
final class TimelineScrollController {
    private(set) var isPinnedToEnd = true
    private(set) var isUserScrolling = false
    private(set) var isProgrammaticScrolling = false
    private(set) var shouldShowScrollToEnd = false

    var autoFollowEnabled = true

    /// Content offset captured while the user was away from the end. Survives a
    /// navigation cover that jumps geometry to a bogus pin, and a collection
    /// view recreated on return, so we can put them back instead of snapping
    /// to the end.
    private(set) var userUnpinnedContentOffsetY: CGFloat?

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
        if userUnpinnedContentOffsetY != nil {
            autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: false)
        } else {
            autoFollowState.updatePinnedState(
                isPinnedToEnd: isPinnedToEnd,
                messageCount: messageCount
            )
        }
    }

    /// Record the offset of an in-progress user scroll.
    ///
    /// Only the user's own scroll decides whether they reached the end. A pinned
    /// sample outside an active gesture (navigation cover, inset change, a late
    /// idle check after the gesture already ended) must not forget their place.
    func noteUserScrollOffset(_ y: CGFloat) {
        guard isUserScrolling else { return }
        userUnpinnedContentOffsetY = isPinnedToEnd ? nil : y
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

    func noteObservedMessageCount(_ messageCount: Int) {
        autoFollowState.observeCount(messageCount)
        self.messageCount = messageCount
        refreshDerivedState()
    }

    func notifyContentGrew() {
        guard autoFollowState.shouldEnsureLayoutEnd(autoFollowEnabled: autoFollowEnabled) else {
            return
        }
        requestScrollToEnd(animated: false)
    }

    func scrollToEndAnimated() {
        userUnpinnedContentOffsetY = nil
        autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: true)
        requestScrollToEnd(animated: true)
    }

    /// Scroll to a row (a failure the activity pill points at) and flash it.
    /// Follow stops until the reveal settles, then holds that place like a user scroll.
    func revealItem(id: String) {
        scrollGeneration += 1
        isProgrammaticScrolling = false
        programmaticPinRetryCount = 0
        autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: false)
        refreshDerivedState()
        performer?.performRevealItem(id: id)
    }

    /// A reveal came to rest at `contentOffsetY`: hold it unless it landed at the end.
    func noteRevealSettled(contentOffsetY: CGFloat) {
        userUnpinnedContentOffsetY = isPinnedToEnd ? nil : contentOffsetY
        autoFollowState.markProgrammaticScrollSettled(isPinnedToEnd: isPinnedToEnd)
    }

    func requestScrollToEnd(animated: Bool) {
        // Follow and inset re-pins must not override a place the user is holding.
        // The scroll-to-end pill clears that place before calling in.
        guard userUnpinnedContentOffsetY == nil else { return }
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

    /// The user's drag, fling, or deceleration came to rest at `contentOffsetY`.
    /// Landing at the end clears the held place and re-arms follow; landing away
    /// holds that offset. Ignored when no gesture is active, so a duplicate or
    /// late end cannot read a covering route's pinned geometry as the user's.
    func endUserScrolling(contentOffsetY: CGFloat? = nil) {
        guard isUserScrolling else { return }
        isUserScrolling = false
        refreshDerivedState()
        if isPinnedToEnd {
            userUnpinnedContentOffsetY = nil
        } else if let contentOffsetY {
            userUnpinnedContentOffsetY = contentOffsetY
        }
        autoFollowState.markUserScrollEnded(isPinnedToEnd: isPinnedToEnd)
    }

    /// A navigation cover took the timeline away before the gesture ended.
    /// End it without trusting pinned geometry, which the cover may have
    /// produced: keep the held place and leave follow as the gesture left it.
    func interruptUserScrolling(contentOffsetY: CGFloat) {
        guard isUserScrolling else { return }
        isUserScrolling = false
        refreshDerivedState()
        if !isPinnedToEnd {
            userUnpinnedContentOffsetY = contentOffsetY
        }
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
