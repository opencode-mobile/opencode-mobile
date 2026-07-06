struct MessageListAutoFollowState {
    private var observedMessageCount: Int
    private var shouldFollowNextAppend = false

    init(initialMessageCount: Int) {
        observedMessageCount = initialMessageCount
    }

    mutating func updatePinnedState(isPinnedToEnd: Bool, messageCount: Int) {
        guard messageCount == observedMessageCount else { return }

        shouldFollowNextAppend = isPinnedToEnd
    }

    mutating func markProgrammaticScrollSettled(isPinnedToEnd: Bool) {
        shouldFollowNextAppend = isPinnedToEnd
    }

    func shouldFollowLayoutChange(autoFollowEnabled: Bool, isPinnedToEnd: Bool) -> Bool {
        autoFollowEnabled && shouldFollowNextAppend && !isPinnedToEnd
    }

    mutating func onMessageCountChanged(messageCount: Int, autoFollowEnabled: Bool) -> Bool {
        let shouldFollow = autoFollowEnabled && messageCount > observedMessageCount && shouldFollowNextAppend
        observedMessageCount = messageCount
        return shouldFollow
    }
}
