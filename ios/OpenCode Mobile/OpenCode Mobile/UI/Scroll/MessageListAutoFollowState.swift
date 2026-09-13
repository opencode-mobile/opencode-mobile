struct MessageListAutoFollowState {
    private var observedMessageCount: Int
    private var shouldFollowNextAppend = false

    init(initialMessageCount: Int) {
        observedMessageCount = initialMessageCount
    }

    mutating func updatePinnedState(isPinnedToEnd: Bool, messageCount: Int) {
        guard messageCount == observedMessageCount else { return }

        // Geometry can report "unpinned" while estimated heights are still resolving
        // after a programmatic scroll-to-end. Only arm from geometry here; disarming
        // happens from user-scroll callbacks (or explicit markProgrammaticScrollSettled).
        if isPinnedToEnd {
            shouldFollowNextAppend = true
        }
    }

    mutating func markProgrammaticScrollSettled(isPinnedToEnd: Bool) {
        shouldFollowNextAppend = isPinnedToEnd
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
