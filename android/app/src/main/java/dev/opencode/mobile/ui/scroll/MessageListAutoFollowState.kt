package dev.opencode.mobile.ui.scroll

internal class MessageListAutoFollowState(
    initialMessageCount: Int,
) {
    private var observedMessageCount = initialMessageCount
    private var shouldFollowNextAppend = false

    fun updatePinnedState(
        isPinnedToEnd: Boolean,
        messageCount: Int,
    ) {
        if (messageCount == observedMessageCount) {
            shouldFollowNextAppend = isPinnedToEnd
        }
    }

    fun markProgrammaticScrollSettled(isPinnedToEnd: Boolean) {
        shouldFollowNextAppend = isPinnedToEnd
    }

    fun onMessageCountChanged(
        messageCount: Int,
        autoFollowEnabled: Boolean,
    ): Boolean {
        val shouldFollow =
            autoFollowEnabled &&
                messageCount > observedMessageCount &&
                shouldFollowNextAppend

        observedMessageCount = messageCount
        return shouldFollow
    }
}
