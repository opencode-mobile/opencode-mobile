package dev.opencode.mobile.ui.scroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class MessageListAutoFollowState(
    initialMessageCount: Int,
) {
    private var observedMessageCount = initialMessageCount
    private var shouldFollowNextAppend by mutableStateOf(false)

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

    fun shouldFollowLayoutChange(
        autoFollowEnabled: Boolean,
        isPinnedToEnd: Boolean,
    ): Boolean = autoFollowEnabled && shouldFollowNextAppend && !isPinnedToEnd

    fun shouldEnsureLayoutEnd(autoFollowEnabled: Boolean): Boolean = autoFollowEnabled && shouldFollowNextAppend

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
