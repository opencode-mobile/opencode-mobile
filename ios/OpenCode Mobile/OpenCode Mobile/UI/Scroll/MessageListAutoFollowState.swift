struct MessageListAutoFollowState {
    private var observedMessageCount: Int
    private var shouldFollowNextAppend = false
    /// Set when the user scrolls away from the end. A covering destination
    /// (subagent, tool group) can report a bogus pin; that must not re-arm follow.
    private var userDisarmedFollow = false

    init(initialMessageCount: Int) {
        observedMessageCount = initialMessageCount
    }

    mutating func updatePinnedState(isPinnedToEnd: Bool, messageCount: Int) {
        guard messageCount == observedMessageCount else { return }

        // Geometry can report "unpinned" while estimated heights are still resolving
        // after a programmatic scroll-to-end. Only arm from geometry here; disarming
        // happens from user-scroll callbacks (or explicit markProgrammaticScrollSettled).
        // A later pinned sample after the user scrolled away is the same class of
        // lie (navigation cover, inset change) and must not re-arm.
        if isPinnedToEnd, !userDisarmedFollow {
            shouldFollowNextAppend = true
        }
    }

    mutating func markProgrammaticScrollSettled(isPinnedToEnd: Bool) {
        shouldFollowNextAppend = isPinnedToEnd
        userDisarmedFollow = !isPinnedToEnd
    }

    /// A user drag, fling, or deceleration came to rest. Unlike a geometry
    /// sample, landing at the end is the user's own choice and re-arms follow
    /// even after an earlier disarm. Landing away leaves the disarm that
    /// `beginUserScrolling` already applied.
    mutating func markUserScrollEnded(isPinnedToEnd: Bool) {
        guard isPinnedToEnd else { return }
        userDisarmedFollow = false
        shouldFollowNextAppend = true
    }

    func shouldFollowLayoutChange(autoFollowEnabled: Bool, isPinnedToEnd: Bool) -> Bool {
        autoFollowEnabled && shouldFollowNextAppend && !isPinnedToEnd
    }

    func shouldEnsureLayoutEnd(autoFollowEnabled: Bool) -> Bool {
        autoFollowEnabled && shouldFollowNextAppend
    }

    mutating func onMessageCountChanged(messageCount: Int, autoFollowEnabled: Bool) -> Bool {
        let shouldFollow = autoFollowEnabled && messageCount > observedMessageCount && shouldFollowNextAppend
        observedMessageCount = messageCount
        return shouldFollow
    }

    mutating func observeCount(_ messageCount: Int) {
        observedMessageCount = messageCount
    }
}
